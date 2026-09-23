# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends openssl postgresql-client && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar
COPY diagnose-db.sh /app/diagnose-db.sh
RUN chmod +x /app/diagnose-db.sh

EXPOSE 8080

ENTRYPOINT ["/app/diagnose-db.sh"]