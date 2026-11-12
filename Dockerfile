# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# PostgreSQL client tools: psql (diagnose-db.sh) and pg_dump / pg_restore
# (Take Backup). pg_dump refuses to dump a server NEWER than itself, and the
# Ubuntu base image only ships an older client, so the client comes from the
# official PostgreSQL apt repository (PGDG) at a pinned major version. Set
# PG_CLIENT_MAJOR >= the database's major version (SHOW server_version;) -
# a newer client dumps older servers fine. See docs/backup.md.
ARG PG_CLIENT_MAJOR=17

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates curl openssl && \
    install -d /usr/share/postgresql-common/pgdg && \
    curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc && \
    . /etc/os-release && \
    echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends postgresql-client-${PG_CLIENT_MAJOR} && \
    apt-get purge -y --auto-remove curl && \
    rm -rf /var/lib/apt/lists/* && \
    pg_dump --version && pg_restore --version

COPY --from=build /app/target/*.jar app.jar
COPY diagnose-db.sh /app/diagnose-db.sh
RUN chmod +x /app/diagnose-db.sh

EXPOSE 8080

ENTRYPOINT ["/app/diagnose-db.sh"]