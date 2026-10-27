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
 * Runs the real Flyway chain V2..V12 on a disposable PostgreSQL (same gating
 * and warning as ExportGrantMigrationPostgresTest: CRM_TEST_PG_URL, the public
 * schema is DROPPED) and checks V12: the send-log table and its unique
 * constraint, and that the AMC template seed is repeatable and never overwrites
 * or duplicates an admin-managed template.
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

        assertThat(scalar("SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("12");

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
}
