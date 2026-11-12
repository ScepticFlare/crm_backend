package com.compact.crm.service.backup;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Safety gate for BackupPostgresRoundTripTest, which DROPS the public schema of
// its database. Nothing destructive may run unless every check passes. A
// failed check is a test FAILURE with the reasons listed, never a skip, so an
// unsafe configuration cannot look like a passing run.
//
// Configuration checks (before any connection is opened), from the environment:
//   - CRM_TEST_PG_URL is a plain single-host jdbc:postgresql URL
//   - its only allowed query parameter is sslmode (pgjdbc reads PGHOST /
//     PGDBNAME / PGPORT from URL parameters, which could redirect the connection)
//   - the host is exactly localhost, 127.0.0.1 or ::1
//   - the database name ends in _restore_test
//   - CRM_TEST_PG_CONFIRM_DROP is exactly that database name
//   - CRM_TEST_PG_USER is set explicitly and is not "postgres"
// Server checks (read-only catalog queries, run on the SAME connection
// immediately before each DROP SCHEMA):
//   - current_database() is that database
//   - current_user and session_user are both exactly CRM_TEST_PG_USER
//   - that role is NOT a superuser, DIRECTLY owns the database, and owns no
//     other database
//   - before the FIRST drop only (PublicSchema.MUST_BE_EMPTY): the public
//     schema exists and holds no user objects at all. Fails closed - a missing
//     schema, a missing row or a NULL value is a refusal, never "empty".
final class DisposableDatabaseGuard {

    // What the public schema may contain at the moment of a DROP.
    enum PublicSchema {
        // first DROP: the database must still be untouched
        MUST_BE_EMPTY,
        // second DROP: the test itself created the CRM schema there by then
        MAY_CONTAIN_TEST_OBJECTS
    }

    // Column 6 counts every database the role owns, so a role that also owns a
    // real database is refused (the run must use a role that owns only this one).
    static final String IDENTITY_SQL =
            "SELECT current_database(), current_user, session_user, r.rolsuper, d.datdba = r.oid, "
                    + "(SELECT count(*) FROM pg_database o WHERE o.datdba = r.oid) "
                    + "FROM pg_roles r JOIN pg_database d ON d.datname = current_database() "
                    + "WHERE r.rolname = current_user";

    // Every catalog whose objects live in a schema. Tables, views, sequences,
    // indexes, materialized views and foreign tables are pg_class rows;
    // functions, procedures and aggregates are pg_proc rows; enums, domains,
    // composite and range types are pg_type rows; the rest cover collations,
    // conversions, operators, operator classes/families, text search objects,
    // extended statistics and extensions installed into public.
    static final String PUBLIC_OBJECTS_SQL =
            "SELECT n.oid IS NOT NULL, "
                    + "(SELECT count(*) FROM pg_class WHERE relnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_proc WHERE pronamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_type WHERE typnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_collation WHERE collnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_conversion WHERE connamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_operator WHERE oprnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_opclass WHERE opcnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_opfamily WHERE opfnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_ts_config WHERE cfgnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_ts_dict WHERE dictnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_ts_parser WHERE prsnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_ts_template WHERE tmplnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_statistic_ext WHERE stxnamespace = n.oid)"
                    + " + (SELECT count(*) FROM pg_extension WHERE extnamespace = n.oid) "
                    + "FROM (SELECT to_regnamespace('public')::oid AS oid) n";

    static final String SUFFIX = "_restore_test";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final Set<String> ALLOWED_PARAMS = Set.of("sslmode");

    private DisposableDatabaseGuard() {
    }

    static PostgresTarget checkConfiguration(Map<String, String> env) {

        List<String> problems = new ArrayList<>();
        String url = env.get("CRM_TEST_PG_URL");
        String user = env.get("CRM_TEST_PG_USER");
        String confirm = env.get("CRM_TEST_PG_CONFIRM_DROP");

        PostgresTarget target = null;
        try {
            target = PostgresTarget.fromJdbc(url, user, env.getOrDefault("CRM_TEST_PG_PASSWORD", ""));
        } catch (RuntimeException e) {
            problems.add("CRM_TEST_PG_URL is not a plain jdbc:postgresql://host:port/database URL (" + e.getMessage() + ")");
        }

        if (url != null) {
            int q = url.indexOf('?');
            if (q >= 0) {
                for (String pair : url.substring(q + 1).split("&")) {
                    String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                    if (!ALLOWED_PARAMS.contains(key.toLowerCase())) {
                        problems.add("CRM_TEST_PG_URL has the query parameter '" + key + "'; only sslmode is allowed");
                    }
                }
            }
        }

        if (target != null) {
            if (!LOCAL_HOSTS.contains(target.host())) {
                problems.add("host '" + target.host() + "' is not localhost, 127.0.0.1 or ::1");
            }
            if (!target.database().endsWith(SUFFIX) || target.database().length() == SUFFIX.length()) {
                problems.add("database '" + target.database() + "' does not end in " + SUFFIX);
            }
            if (confirm == null || !confirm.equals(target.database())) {
                problems.add("CRM_TEST_PG_CONFIRM_DROP must be exactly the database name '" + target.database() + "'");
            }
        }

        if (user == null || user.isBlank()) {
            problems.add("CRM_TEST_PG_USER must be set explicitly (a dedicated non-superuser role)");
        } else if (user.equalsIgnoreCase("postgres")) {
            problems.add("CRM_TEST_PG_USER must not be the postgres superuser");
        }

        if (!problems.isEmpty()) {
            throw refusal(problems);
        }
        return target;
    }

    static void checkConnection(Connection c, PostgresTarget target, PublicSchema publicSchema) throws SQLException {

        String expectedUser = target.username();
        List<String> problems = new ArrayList<>();

        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(IDENTITY_SQL)) {
            if (!rs.next()) {
                throw refusal(List.of("could not read the connected role and database"));
            }
            String database = rs.getString(1);
            String currentUser = rs.getString(2);
            String sessionUser = rs.getString(3);
            boolean superuser = rs.getBoolean(4);
            boolean superuserUnknown = rs.wasNull();
            boolean owner = rs.getBoolean(5);
            boolean ownerUnknown = rs.wasNull();
            long ownedDatabases = rs.getLong(6);
            boolean ownedUnknown = rs.wasNull();

            if (!target.database().equals(database)) {
                problems.add("connected to database '" + database + "', expected '" + target.database() + "'");
            }
            if (!expectedUser.equals(currentUser)) {
                problems.add("current_user is '" + currentUser + "', expected CRM_TEST_PG_USER '" + expectedUser + "'");
            }
            if (!expectedUser.equals(sessionUser)) {
                problems.add("session_user is '" + sessionUser + "', expected CRM_TEST_PG_USER '" + expectedUser + "'");
            }
            if (superuserUnknown || superuser) {
                problems.add("the connected role is a superuser (or its status is unknown); use a dedicated non-superuser role");
            }
            if (ownerUnknown || !owner) {
                problems.add("the connected role does not directly own database '" + database + "'");
            }
            if (ownedUnknown || ownedDatabases != 1) {
                problems.add("the connected role must own exactly one database (this one); it owns "
                        + (ownedUnknown ? "an unknown number of" : String.valueOf(ownedDatabases)) + " databases");
            }
        }

        if (problems.isEmpty() && publicSchema == PublicSchema.MUST_BE_EMPTY) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(PUBLIC_OBJECTS_SQL)) {
                if (!rs.next()) {
                    problems.add("could not establish that the public schema is empty (no result)");
                } else {
                    boolean exists = rs.getBoolean(1);
                    boolean existsUnknown = rs.wasNull();
                    long objects = rs.getLong(2);
                    boolean objectsUnknown = rs.wasNull();
                    if (existsUnknown || !exists) {
                        problems.add("the database has no public schema, so it is not a fresh database from template0");
                    } else if (objectsUnknown) {
                        problems.add("could not establish that the public schema is empty (no object count)");
                    } else if (objects != 0) {
                        problems.add("the public schema is not empty (" + objects
                                + " objects); the test only runs on a freshly created, empty database");
                    }
                }
            }
        }

        if (!problems.isEmpty()) {
            throw refusal(problems);
        }
    }

    private static AssertionError refusal(List<String> problems) {
        return new AssertionError("Refusing to run BackupPostgresRoundTripTest - it DROPS the public schema. "
                + "Nothing was changed. Fix: " + String.join("; ", problems) + ". See docs/backup.md.");
    }
}
