package com.compact.crm.service.backup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Restore verification: a real pg_dump of a real CRM schema, checked with the
 * same code the server uses, then RESTORED with pg_restore and compared row for
 * row with what was dumped.
 *
 * DESTRUCTIVE - it DROPS the public schema of CRM_TEST_PG_URL (twice: before
 * seeding and before the restore). It is skipped unless CRM_TEST_PG_URL is set;
 * once it is set, DisposableDatabaseGuard must pass before anything is changed,
 * otherwise the test FAILS (it never silently skips):
 *   - host exactly localhost / 127.0.0.1 / ::1, no URL parameters except sslmode
 *   - database name ends in _restore_test
 *   - CRM_TEST_PG_CONFIRM_DROP equals that database name exactly
 *   - on the live connection, right before EACH drop: current_database() is
 *     that name, current_user = session_user = CRM_TEST_PG_USER (not postgres),
 *     the role is not a superuser and directly owns the database
 *   - before the FIRST drop only: the public schema holds no objects at all
 * Needs pg_dump / pg_restore on the PATH or in CRM_TEST_PG_BIN; the password
 * comes from CRM_TEST_PG_PASSWORD. Windows PowerShell steps: docs/backup.md.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class BackupPostgresRoundTripTest {

    private static final String URL = System.getenv("CRM_TEST_PG_URL");
    private static final String USER = System.getenv("CRM_TEST_PG_USER");
    private static final String PASSWORD = System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", "");
    private static final String BIN = System.getenv().getOrDefault("CRM_TEST_PG_BIN", "");

    // The role every test employee gets (employees.role_id is NOT NULL since V2),
    // looked up by name - never a hard-coded id.
    private static final String EMPLOYEE_ROLE_ID = "(SELECT id FROM roles WHERE name = 'EMPLOYEE')";

    @TempDir
    Path dir;

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(URL, USER, PASSWORD);
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String tool(String name) {
        return BIN.isBlank() ? name : Path.of(BIN, name).toString();
    }

    private BackupProperties properties() {
        BackupProperties p = new BackupProperties();
        p.setPgDumpPath(tool("pg_dump"));
        p.setPgRestorePath(tool("pg_restore"));
        p.setTempDir(dir.resolve("work"));
        p.setDumpTimeout(Duration.ofMinutes(5));
        return p;
    }

    // row count + a digest of every row's text, per table in public
    private Map<String, String> snapshot() throws SQLException {
        Map<String, String> out = new TreeMap<>();
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String t : tables) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) || ':' || md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) "
                        + "FROM public.\"" + t + "\" x")) {
                    rs.next();
                    out.put(t, rs.getString(1));
                }
            }
        }
        return out;
    }

    @Test
    void aBackup_restoresToExactlyTheSameData() throws Exception {

        // 0. SAFETY: every check must pass before anything is changed (fails, never skips)
        PostgresTarget target = DisposableDatabaseGuard.checkConfiguration(System.getenv());

        // 1. a CRM schema built by the real migrations, with some data. The first
        //    DROP additionally requires the public schema to be completely empty.
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            DisposableDatabaseGuard.checkConnection(c, target, DisposableDatabaseGuard.PublicSchema.MUST_BE_EMPTY);
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }
        Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration")
                .baselineVersion("1").baselineOnMigrate(true).load().migrate();

        // employees.role_id is NOT NULL (V2): use the EMPLOYEE role that V2 seeds
        assertThat(queryLong("SELECT count(*) FROM roles WHERE name = 'EMPLOYEE'"))
                .as("the EMPLOYEE role seeded by migration V2").isEqualTo(1);

        for (int i = 1; i <= 3; i++) {
            exec("INSERT INTO employees (name, email, phone, password, is_active, role_id) VALUES "
                    + "('Restore Ünïcode ''" + i + "''', 'restore" + i + "@pg.example', '97000000" + (10 + i) + "', 'x', true, "
                    + EMPLOYEE_ROLE_ID + ")");
        }
        exec("INSERT INTO activity_log (employee_name, action, module, description, created_at) "
                + "VALUES ('Restore', 'BACKUP_STARTED', 'SYSTEM', 'line1\nline2 \"quoted\" \\ backslash', now())");
        exec("INSERT INTO db_backup (status, initiated_by_name, started_at) VALUES ('FAILED', 'Restore', now())");

        Map<String, String> before = snapshot();
        assertThat(before).containsKeys("employees", "flyway_schema_history", "db_backup", "activity_log");

        // 2. dump and check it exactly as the server does
        PgDatabaseDumper dumper = new PgDatabaseDumper(properties(), new ProcessCommandExecutor(dir.resolve("work")),
                URL, USER, PASSWORD);
        Path archive = dir.resolve("crm.dump");
        dumper.dump(archive);
        PgDumpRunner.ArchiveInfo info = dumper.verify(archive);

        assertThat(Files.size(archive)).isPositive();
        assertThat(info.tables()).contains("public.employees", "public.flyway_schema_history", "public.db_backup");
        assertThat(info.tableCount()).isEqualTo(before.size());
        assertThat(info.serverVersion()).isNotBlank();

        // a truncated copy of the same archive must be rejected
        byte[] bytes = Files.readAllBytes(archive);
        Path truncated = Files.write(dir.resolve("truncated.dump"), java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertThatThrownBy(() -> dumper.verify(truncated)).isInstanceOf(BackupFailedException.class);

        // 3. lose everything, then restore from the archive (docs/backup.md runbook)
        CommandExecutor executor = new ProcessCommandExecutor(dir.resolve("work"));
        CommandExecutor.CommandResult list = executor.run(List.of(tool("pg_restore"), "--list", archive.toString()),
                Map.of(), Duration.ofMinutes(2));
        boolean archiveCreatesSchema = list.stdout().matches("(?s).*\\bSCHEMA - public\\b.*");

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            // by now the schema holds what step 1 created, so no emptiness check here
            DisposableDatabaseGuard.checkConnection(c, target, DisposableDatabaseGuard.PublicSchema.MAY_CONTAIN_TEST_OBJECTS);
            st.execute("DROP SCHEMA public CASCADE");
        }
        if (!archiveCreatesSchema) {
            exec("CREATE SCHEMA public");
        }
        assertThat(snapshot()).isEmpty();

        CommandExecutor.CommandResult restore = executor.run(List.of(tool("pg_restore"),
                        "--no-owner", "--no-privileges", "--exit-on-error", "--single-transaction",
                        "--host=" + target.host(), "--port=" + target.port(), "--username=" + target.username(),
                        "--dbname=" + target.database(), "--no-password", archive.toString()),
                Map.of("PGPASSWORD", PASSWORD, "PGSSLMODE", "prefer"), Duration.ofMinutes(5));
        assertThat(restore.exitCode()).as("pg_restore: %s", restore.stderr()).isZero();

        // 4. identical data, valid migration history, working sequences
        assertThat(snapshot()).isEqualTo(before);

        Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration")
                .baselineVersion("1").baselineOnMigrate(true).load().validate();

        exec("INSERT INTO employees (name, email, phone, password, is_active, role_id) "
                + "VALUES ('After Restore', 'after-restore@pg.example', '9700000099', 'x', true, " + EMPLOYEE_ROLE_ID + ")");
    }
}
