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
 * Runs the real Flyway chain up to V14.1 on a disposable PostgreSQL (same gating
 * and warning as the other *PostgresTest classes: CRM_TEST_PG_URL, the public
 * schema is DROPPED) and checks V14.1: existing bulk email history survives, the
 * mailbox table enforces one mailbox per employee and per address, rows are
 * deleted with their employee, and the migration is safe to re-run.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class MailboxMigrationPostgresTest {

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

    private void migrateTo(String target) throws Exception {
        Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration")
                .baselineVersion("1").baselineOnMigrate(true).target(target).load().migrate();
    }

    @Test
    void v18_keepsExistingHistory_andEnforcesOneMailboxPerEmployeeAndAddress() throws Exception {

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }

        migrateTo("14");

        // history written through Brevo before the switch
        exec("INSERT INTO email_send_log (batch_id, target, target_id, recipient_email, status, created_at, updated_at) "
                + "VALUES ('brevo-batch-0001','LEAD',7,'a@b.example','ACCEPTED',now(),now())");

        migrateTo("14.1");

        assertThat(scalar("SELECT count(*) FROM email_send_log WHERE batch_id = 'brevo-batch-0001' AND sender_email IS NULL"))
                .isEqualTo("1");

        String employeeId = scalar("INSERT INTO employees (name, email, phone, password, is_active) "
                + "VALUES ('Mailbox Owner', 'mailbox-owner@pg.example', '9799999999', 'x', true) RETURNING id");

        exec("INSERT INTO email_mailbox_account (employee_id, provider, email_address, provider_subject, status, "
                + "refresh_token_encrypted, connected_at, updated_at) VALUES (" + employeeId
                + ", 'GOOGLE', 'a@company.example', 'sub-1', 'CONNECTED', 'k.x', now(), now())");

        // a second mailbox for the same employee, or the same address for anyone, is refused
        assertThatThrownBy(() -> exec("INSERT INTO email_mailbox_account (employee_id, provider, email_address, "
                + "provider_subject, status, connected_at, updated_at) VALUES (" + employeeId
                + ", 'MICROSOFT', 'b@company.example', 'sub-2', 'CONNECTED', now(), now())"))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> exec("INSERT INTO email_mailbox_account (employee_id, provider, email_address, "
                + "provider_subject, status, connected_at, updated_at) VALUES (" + employeeId
                + ", 'YAHOO', 'c@company.example', 'sub-3', 'CONNECTED', now(), now())"))
                .isInstanceOf(SQLException.class);

        // re-running V14.1 changes nothing
        try (Connection c = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/V14_1__employee_mailbox_accounts_and_oauth_state.sql"));
        }
        assertThat(scalar("SELECT count(*) FROM email_mailbox_account")).isEqualTo("1");

        // the credential goes with its employee
        exec("DELETE FROM employees WHERE id = " + employeeId);
        assertThat(scalar("SELECT count(*) FROM email_mailbox_account")).isEqualTo("0");
    }
}
