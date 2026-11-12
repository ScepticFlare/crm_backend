package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The safety gate of the destructive BackupPostgresRoundTripTest, checked
// without any database: configuration from a fake environment, server checks
// against a mocked JDBC connection.
class DisposableDatabaseGuardTest {

    private static Map<String, String> env(String url, String user, String confirm) {
        Map<String, String> env = new HashMap<>();
        if (url != null) env.put("CRM_TEST_PG_URL", url);
        if (user != null) env.put("CRM_TEST_PG_USER", user);
        if (confirm != null) env.put("CRM_TEST_PG_CONFIRM_DROP", confirm);
        env.put("CRM_TEST_PG_PASSWORD", "throwaway");
        return env;
    }

    private static final String GOOD_URL = "jdbc:postgresql://localhost:5432/crm_backup_restore_test";

    private static void refused(Map<String, String> env, String reason) {
        assertThatThrownBy(() -> DisposableDatabaseGuard.checkConfiguration(env))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("Refusing to run BackupPostgresRoundTripTest")
                .hasMessageContaining(reason)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("throwaway"));
    }

    @Test
    void aLocalDisposableDatabaseWithMatchingConfirmation_isAccepted() {

        for (String url : new String[]{GOOD_URL, "jdbc:postgresql://127.0.0.1:5432/crm_backup_restore_test",
                "jdbc:postgresql://[::1]:5432/crm_backup_restore_test",
                "jdbc:postgresql://localhost:5432/crm_backup_restore_test?sslmode=disable"}) {
            PostgresTarget t = DisposableDatabaseGuard.checkConfiguration(env(url, "crm_restore_test", "crm_backup_restore_test"));
            assertThat(t.database()).isEqualTo("crm_backup_restore_test");
        }
    }

    @Test
    void aRemoteOrLookalikeHost_isRefused() {

        refused(env("jdbc:postgresql://aws-0-region.pooler.supabase.com:5432/crm_backup_restore_test",
                "crm_restore_test", "crm_backup_restore_test"), "is not localhost");
        refused(env("jdbc:postgresql://localhost.example.com:5432/crm_backup_restore_test",
                "crm_restore_test", "crm_backup_restore_test"), "is not localhost");
        refused(env("jdbc:postgresql://127.0.0.2:5432/crm_backup_restore_test",
                "crm_restore_test", "crm_backup_restore_test"), "is not localhost");
    }

    @Test
    void urlParametersThatCouldRedirectTheConnection_areRefused() {

        refused(env(GOOD_URL + "?PGHOST=db.example.com", "crm_restore_test", "crm_backup_restore_test"),
                "query parameter 'PGHOST'");
        refused(env(GOOD_URL + "?sslmode=require&PGDBNAME=postgres", "crm_restore_test", "crm_backup_restore_test"),
                "query parameter 'PGDBNAME'");
    }

    @Test
    void aDatabaseNotNamedAsDisposable_isRefused() {

        refused(env("jdbc:postgresql://localhost:5432/postgres", "crm_restore_test", "postgres"), "does not end in _restore_test");
        refused(env("jdbc:postgresql://localhost:5432/crm_test_db", "crm_restore_test", "crm_test_db"), "does not end in _restore_test");
        refused(env("jdbc:postgresql://localhost:5432/_restore_test", "crm_restore_test", "_restore_test"), "does not end in _restore_test");
    }

    @Test
    void theConfirmationMustMatchTheDatabaseExactly() {

        refused(env(GOOD_URL, "crm_restore_test", null), "CRM_TEST_PG_CONFIRM_DROP must be exactly");
        refused(env(GOOD_URL, "crm_restore_test", "yes"), "CRM_TEST_PG_CONFIRM_DROP must be exactly");
        refused(env(GOOD_URL, "crm_restore_test", "CRM_BACKUP_RESTORE_TEST"), "CRM_TEST_PG_CONFIRM_DROP must be exactly");
        refused(env(GOOD_URL, "crm_restore_test", "crm_backup_restore_test "), "CRM_TEST_PG_CONFIRM_DROP must be exactly");
    }

    @Test
    void thePostgresSuperuserOrAMissingUser_isRefused() {

        refused(env(GOOD_URL, "postgres", "crm_backup_restore_test"), "must not be the postgres superuser");
        refused(env(GOOD_URL, null, "crm_backup_restore_test"), "must be set explicitly");
    }

    @Test
    void aMissingOrNonPostgresUrl_isRefused() {

        refused(env(null, "crm_restore_test", "x_restore_test"), "not a plain jdbc:postgresql");
        refused(env("jdbc:h2:mem:x_restore_test", "crm_restore_test", "x_restore_test"), "not a plain jdbc:postgresql");
    }

    @Test
    void everyProblemIsReported_atOnce() {

        assertThatThrownBy(() -> DisposableDatabaseGuard.checkConfiguration(
                env("jdbc:postgresql://db.example.com:5432/postgres", "postgres", null)))
                .hasMessageContaining("is not localhost")
                .hasMessageContaining("does not end in _restore_test")
                .hasMessageContaining("CRM_TEST_PG_CONFIRM_DROP")
                .hasMessageContaining("postgres superuser");
    }

    // ---- server checks (mocked connection, no database) -------------------

    private static final String DB = "crm_backup_restore_test";
    private static final String ROLE = "crm_restore_test";

    // Answers the guard's two catalog queries with fixed values. objects == null
    // means "the count came back NULL"; emptyQueryHasRow == false means "no row".
    private static final class FakeServer {
        String database = DB;
        String currentUser = ROLE;
        String sessionUser = ROLE;
        Boolean superuser = false;
        Boolean owner = true;
        Long ownedDatabases = 1L;
        boolean identityHasRow = true;
        Boolean publicExists = true;
        Long objects = 0L;
        boolean emptyQueryHasRow = true;
        int emptinessQueries;

        Connection connection() throws Exception {
            Statement st = mock(Statement.class);
            when(st.executeQuery(anyString())).thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                if (sql.equals(DisposableDatabaseGuard.IDENTITY_SQL)) {
                    return identity();
                }
                if (sql.equals(DisposableDatabaseGuard.PUBLIC_OBJECTS_SQL)) {
                    emptinessQueries++;
                    return emptiness();
                }
                throw new AssertionError("unexpected SQL: " + sql);
            });
            Connection c = mock(Connection.class);
            when(c.createStatement()).thenReturn(st);
            return c;
        }

        private ResultSet identity() throws Exception {
            ResultSet rs = mock(ResultSet.class);
            final boolean[] lastNull = {false};
            when(rs.next()).thenReturn(identityHasRow);
            when(rs.getString(1)).thenReturn(database);
            when(rs.getString(2)).thenReturn(currentUser);
            when(rs.getString(3)).thenReturn(sessionUser);
            when(rs.getBoolean(4)).thenAnswer(i -> { lastNull[0] = superuser == null; return superuser != null && superuser; });
            when(rs.getBoolean(5)).thenAnswer(i -> { lastNull[0] = owner == null; return owner != null && owner; });
            when(rs.getLong(6)).thenAnswer(i -> { lastNull[0] = ownedDatabases == null; return ownedDatabases == null ? 0L : ownedDatabases; });
            when(rs.wasNull()).thenAnswer(i -> lastNull[0]);
            return rs;
        }

        private ResultSet emptiness() throws Exception {
            ResultSet rs = mock(ResultSet.class);
            final boolean[] lastNull = {false};
            when(rs.next()).thenReturn(emptyQueryHasRow);
            when(rs.getBoolean(1)).thenAnswer(i -> { lastNull[0] = publicExists == null; return publicExists != null && publicExists; });
            when(rs.getLong(2)).thenAnswer(i -> { lastNull[0] = objects == null; return objects == null ? 0L : objects; });
            when(rs.wasNull()).thenAnswer(i -> lastNull[0]);
            return rs;
        }
    }

    private static PostgresTarget target() {
        return DisposableDatabaseGuard.checkConfiguration(env(GOOD_URL, ROLE, DB));
    }

    private static void passes(FakeServer server, DisposableDatabaseGuard.PublicSchema mode) throws Exception {
        DisposableDatabaseGuard.checkConnection(server.connection(), target(), mode);
    }

    private static void refusedByServer(FakeServer server, DisposableDatabaseGuard.PublicSchema mode, String reason) {
        assertThatThrownBy(() -> DisposableDatabaseGuard.checkConnection(server.connection(), target(), mode))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("Refusing to run BackupPostgresRoundTripTest")
                .hasMessageContaining(reason);
    }

    private static final DisposableDatabaseGuard.PublicSchema FIRST = DisposableDatabaseGuard.PublicSchema.MUST_BE_EMPTY;
    private static final DisposableDatabaseGuard.PublicSchema SECOND = DisposableDatabaseGuard.PublicSchema.MAY_CONTAIN_TEST_OBJECTS;

    @Test
    void theRightRoleOnAFreshEmptyDatabase_passesBeforeTheFirstDrop() throws Exception {
        FakeServer server = new FakeServer();
        passes(server, FIRST);
        assertThat(server.emptinessQueries).isEqualTo(1);
    }

    @Test
    void theWrongDatabase_isRefusedBeforeEitherDrop() {
        for (DisposableDatabaseGuard.PublicSchema mode : DisposableDatabaseGuard.PublicSchema.values()) {
            FakeServer server = new FakeServer();
            server.database = "crm_dev";
            refusedByServer(server, mode, "connected to database 'crm_dev'");
        }
    }

    @Test
    void currentUserAndSessionUser_mustBothBeTheConfiguredRole() {
        for (DisposableDatabaseGuard.PublicSchema mode : DisposableDatabaseGuard.PublicSchema.values()) {
            FakeServer otherCurrent = new FakeServer();
            otherCurrent.currentUser = "postgres";          // e.g. after SET ROLE
            refusedByServer(otherCurrent, mode, "current_user is 'postgres'");

            FakeServer otherSession = new FakeServer();
            otherSession.sessionUser = "postgres";          // logged in as someone else, then SET ROLE crm_restore_test
            refusedByServer(otherSession, mode, "session_user is 'postgres'");

            FakeServer caseDiffers = new FakeServer();
            caseDiffers.currentUser = "CRM_RESTORE_TEST";
            caseDiffers.sessionUser = "CRM_RESTORE_TEST";
            refusedByServer(caseDiffers, mode, "expected CRM_TEST_PG_USER 'crm_restore_test'");
        }
    }

    @Test
    void aSuperuser_orUnknownSuperuserStatus_isRefused() {
        for (DisposableDatabaseGuard.PublicSchema mode : DisposableDatabaseGuard.PublicSchema.values()) {
            FakeServer superuser = new FakeServer();
            superuser.superuser = true;
            refusedByServer(superuser, mode, "is a superuser");

            FakeServer unknown = new FakeServer();
            unknown.superuser = null;
            refusedByServer(unknown, mode, "is a superuser (or its status is unknown)");
        }
    }

    @Test
    void aRoleThatDoesNotDirectlyOwnTheDatabase_isRefused() {
        for (DisposableDatabaseGuard.PublicSchema mode : DisposableDatabaseGuard.PublicSchema.values()) {
            FakeServer notOwner = new FakeServer();
            notOwner.owner = false;
            refusedByServer(notOwner, mode, "does not directly own database");

            FakeServer unknown = new FakeServer();
            unknown.owner = null;
            refusedByServer(unknown, mode, "does not directly own database");
        }
    }

    @Test
    void aRoleThatAlsoOwnsAnotherDatabase_orAnUnknownNumber_isRefused() {
        for (DisposableDatabaseGuard.PublicSchema mode : DisposableDatabaseGuard.PublicSchema.values()) {
            FakeServer ownsTwo = new FakeServer();
            ownsTwo.ownedDatabases = 2L;                    // e.g. also owns a real dev database
            refusedByServer(ownsTwo, mode, "must own exactly one database (this one); it owns 2 databases");

            FakeServer unknown = new FakeServer();
            unknown.ownedDatabases = null;
            refusedByServer(unknown, mode, "it owns an unknown number of databases");
        }
    }

    @Test
    void noIdentityRow_isRefused() {
        FakeServer server = new FakeServer();
        server.identityHasRow = false;
        refusedByServer(server, FIRST, "could not read the connected role and database");
    }

    @Test
    void beforeTheFirstDrop_aNonEmptyPublicSchema_isRefused() {
        FakeServer server = new FakeServer();
        server.objects = 42L;
        refusedByServer(server, FIRST, "the public schema is not empty (42 objects)");

        FakeServer one = new FakeServer();
        one.objects = 1L;                               // a single leftover view, function or type is enough
        refusedByServer(one, FIRST, "not empty (1 objects)");
    }

    @Test
    void beforeTheFirstDrop_emptinessThatCannotBeEstablished_failsClosed() {
        FakeServer noSchema = new FakeServer();
        noSchema.publicExists = false;
        refusedByServer(noSchema, FIRST, "has no public schema");

        FakeServer unknownSchema = new FakeServer();
        unknownSchema.publicExists = null;
        refusedByServer(unknownSchema, FIRST, "has no public schema");

        FakeServer nullCount = new FakeServer();
        nullCount.objects = null;
        refusedByServer(nullCount, FIRST, "could not establish that the public schema is empty (no object count)");

        FakeServer noRow = new FakeServer();
        noRow.emptyQueryHasRow = false;
        refusedByServer(noRow, FIRST, "could not establish that the public schema is empty (no result)");
    }

    @Test
    void beforeTheSecondDrop_theTestsOwnObjectsAreAllowed_andEmptinessIsNotQueried() throws Exception {
        FakeServer server = new FakeServer();
        server.objects = 500L;
        passes(server, SECOND);
        assertThat(server.emptinessQueries).isZero();
    }

    @Test
    void theEmptinessQuery_coversEverySchemaScopedCatalog_andFailsClosedOnAMissingSchema() {
        String sql = DisposableDatabaseGuard.PUBLIC_OBJECTS_SQL;
        for (String catalog : new String[]{"pg_class", "pg_proc", "pg_type", "pg_collation", "pg_conversion",
                "pg_operator", "pg_opclass", "pg_opfamily", "pg_ts_config", "pg_ts_dict", "pg_ts_parser",
                "pg_ts_template", "pg_statistic_ext", "pg_extension"}) {
            assertThat(sql).as(catalog).contains("FROM " + catalog + " WHERE");
        }
        // to_regnamespace returns NULL (not an error) for a missing schema; the
        // first column then reports "no public schema" instead of counting 0
        assertThat(sql).contains("to_regnamespace('public')").startsWith("SELECT n.oid IS NOT NULL");
        assertThat(DisposableDatabaseGuard.IDENTITY_SQL).contains("current_user", "session_user", "rolsuper", "datdba",
                "(SELECT count(*) FROM pg_database o WHERE o.datdba = r.oid)");
    }
}
