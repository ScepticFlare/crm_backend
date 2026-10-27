package com.compact.crm.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Flyway chain V2..V13 on a disposable PostgreSQL (same gating
 * and warning as ExportGrantMigrationPostgresTest: CRM_TEST_PG_URL, the public
 * schema is DROPPED) and checks V12 (the send-log table and its unique
 * constraint; the AMC template seed is repeatable and never overwrites or
 * duplicates an admin-managed template) and V13 (server-issued batches, the
 * do-not-email list and its audit table, the ADMIN-only management permission).
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class BulkEmailMigrationPostgresTest {

    private static final String URL = System.getenv("CRM_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("CRM_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", "");

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(URL, USER, PASSWORD);
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void runV12Again() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/V12__bulk_email_send_log_and_amc_template.sql"));
        }
    }

    private void runV13Again() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/V13__bulk_email_batches_and_email_suppression.sql"));
        }
    }

    private void freshBaseline() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }
    }

    @Test
    void v12_createsTheSendLog_andSeedsOneEditableAmcTemplateWithoutEverOverwritingIt() throws Exception {

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .baselineVersion("1")
                .baselineOnMigrate(true)
                .load();

        flyway.migrate();

        assertThat(scalar("SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("13");

        // the existing templates are untouched
        assertThat(scalar("SELECT count(*) FROM email_templates WHERE type IN ('PRODUCT_BROCHURE','KEEP_IN_TOUCH')"))
                .isEqualTo("2");

        // exactly one AMC template, as an ordinary admin-managed row
        assertThat(scalar("SELECT count(*) FROM email_templates WHERE type = 'AMC'")).isEqualTo("1");
        assertThat(scalar("SELECT is_active AND is_default FROM email_templates WHERE type = 'AMC'")).isEqualTo("t");

        String subject = scalar("SELECT subject FROM email_templates WHERE type = 'AMC'");
        String body = scalar("SELECT body FROM email_templates WHERE type = 'AMC'");

        // personalisable, with the offer left for an admin - and NO invented price,
        // coverage, duration, warranty or validity (so: no digits, no currency)
        assertThat(body).contains("{{contactPerson}}").contains("{{companyName}}").contains("[[ADMIN:");
        assertThat(subject + body).doesNotContainPattern("[0-9]").doesNotContain("Rs").doesNotContain("%")
                .doesNotContain("₹").doesNotContain("$");

        // the send log exists, and one (batch, record) can only be claimed once
        exec("INSERT INTO email_send_log (batch_id, target, target_id, recipient_email, status, created_at, updated_at) "
                + "VALUES ('batch-pg-0001','LEAD',7,'a@b.example','PENDING',now(),now())");

        assertThatThrownBy(() -> exec("INSERT INTO email_send_log "
                + "(batch_id, target, target_id, recipient_email, status, created_at, updated_at) "
                + "VALUES ('batch-pg-0001','LEAD',7,'a@b.example','PENDING',now(),now())"))
                .hasMessageContaining("uq_email_send_log_batch_record");

        // the same record id under another target or batch is a different claim
        exec("INSERT INTO email_send_log (batch_id, target, target_id, recipient_email, status, created_at, updated_at) "
                + "VALUES ('batch-pg-0001','OPPORTUNITY',7,'a@b.example','PENDING',now(),now())");

        // repeating the migration SQL never duplicates the template...
        runV12Again();
        runV12Again();
        assertThat(scalar("SELECT count(*) FROM email_templates WHERE type = 'AMC'")).isEqualTo("1");

        // ...never overwrites an admin's edits...
        exec("UPDATE email_templates SET subject = 'Our AMC', body = 'Admin wrote this', is_default = FALSE WHERE type = 'AMC'");
        runV12Again();
        assertThat(scalar("SELECT body FROM email_templates WHERE type = 'AMC'")).isEqualTo("Admin wrote this");
        assertThat(scalar("SELECT subject FROM email_templates WHERE type = 'AMC'")).isEqualTo("Our AMC");

        // ...and does not resurrect it when an admin deactivated it
        exec("UPDATE email_templates SET is_active = FALSE WHERE type = 'AMC'");
        runV12Again();
        assertThat(scalar("SELECT count(*) FROM email_templates WHERE type = 'AMC'")).isEqualTo("1");
        assertThat(scalar("SELECT is_active FROM email_templates WHERE type = 'AMC'")).isEqualTo("f");

        // Flyway itself has nothing further to do
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void v13_addsBatchesAndTheDoNotEmailList_onTopOfV12_withoutTouchingExistingData() throws Exception {

        freshBaseline();

        Flyway toV12 = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .baselineVersion("1")
                .baselineOnMigrate(true)
                .target("12")
                .load();

        toV12.migrate();

        assertThat(scalar("SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("12");

        // data that exists in a V12 database
        exec("INSERT INTO email_send_log (batch_id, target, target_id, recipient_email, status, created_at, updated_at) "
                + "VALUES ('legacy-batch-01','LEAD',9,'old@b.example','ACCEPTED',now(),now())");
        exec("UPDATE email_templates SET body = 'Admin wrote this' WHERE type = 'AMC'");

        String permissionsBefore = scalar("SELECT count(*) FROM permissions");
        String grantsBefore = scalar("SELECT count(*) FROM role_permissions");

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .baselineVersion("1")
                .baselineOnMigrate(true)
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(scalar("SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("13");

        // existing rows are untouched
        assertThat(scalar("SELECT count(*) FROM email_send_log WHERE batch_id = 'legacy-batch-01'")).isEqualTo("1");
        assertThat(scalar("SELECT body FROM email_templates WHERE type = 'AMC'")).isEqualTo("Admin wrote this");

        // exactly ONE new permission and ONE new grant (ADMIN, ALL): nothing for MANAGER / EMPLOYEE
        assertThat(scalar("SELECT count(*) FROM permissions")).isEqualTo(String.valueOf(Integer.parseInt(permissionsBefore) + 1));
        assertThat(scalar("SELECT count(*) FROM role_permissions")).isEqualTo(String.valueOf(Integer.parseInt(grantsBefore) + 1));
        assertThat(scalar("SELECT r.name || ':' || rp.scope FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                + "JOIN permissions p ON p.id = rp.permission_id WHERE p.code = 'EMAIL_SUPPRESSION_MANAGE'"))
                .isEqualTo("ADMIN:ALL");

        // nothing is seeded into the new tables
        assertThat(scalar("SELECT count(*) FROM email_suppression")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM email_suppression_audit")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM email_bulk_batch")).isEqualTo("0");

        // batches: the server-issued id is unique
        exec("INSERT INTO email_bulk_batch (batch_id, owner_employee_id, target, subject, body, content_hash, "
                + "recipient_ids, recipient_count, created_at) VALUES "
                + "('11111111-1111-1111-1111-111111111111', 1, 'LEAD', 'S', 'B', 'h', '1,2', 2, now())");

        assertThatThrownBy(() -> exec("INSERT INTO email_bulk_batch (batch_id, owner_employee_id, target, subject, body, "
                + "content_hash, recipient_ids, recipient_count, created_at) VALUES "
                + "('11111111-1111-1111-1111-111111111111', 2, 'LEAD', 'S', 'B', 'h', '3', 1, now())"))
                .hasMessageContaining("uq_email_bulk_batch_batch_id");

        // suppression: one row per normalized address, and only the four reasons
        exec("INSERT INTO email_suppression (email_normalized, reason, source, created_at) "
                + "VALUES ('x@y.example','OPT_OUT','ADMIN_CONSOLE',now())");

        assertThatThrownBy(() -> exec("INSERT INTO email_suppression (email_normalized, reason, source, created_at) "
                + "VALUES ('x@y.example','MANUAL','ADMIN_CONSOLE',now())"))
                .hasMessageContaining("uq_email_suppression_email");

        assertThatThrownBy(() -> exec("INSERT INTO email_suppression (email_normalized, reason, source, created_at) "
                + "VALUES ('z@y.example','SOMETHING_ELSE','ADMIN_CONSOLE',now())"))
                .hasMessageContaining("chk_email_suppression_reason");

        // the de-duplication lookup is indexed
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_email_send_log_email_updated'"))
                .isEqualTo("1");

        // repeatable: running the SQL again changes nothing
        runV13Again();
        runV13Again();
        assertThat(scalar("SELECT count(*) FROM permissions WHERE code = 'EMAIL_SUPPRESSION_MANAGE'")).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM role_permissions")).isEqualTo(String.valueOf(Integer.parseInt(grantsBefore) + 1));
        assertThat(scalar("SELECT count(*) FROM email_suppression")).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM email_bulk_batch")).isEqualTo("1");

        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
