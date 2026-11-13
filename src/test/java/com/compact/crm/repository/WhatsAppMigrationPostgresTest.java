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
 * Runs the real Flyway chain V2..V16 on a DISPOSABLE PostgreSQL and checks the
 * Bulk WhatsApp migrations (V15 templates / consent / permissions, V16 batches /
 * send log / webhook events): constraints, grants, and that re-running them is
 * harmless.
 *
 * WARNING - same gating as BulkEmailMigrationPostgresTest: only runs when
 * CRM_TEST_PG_URL is set, and it DROPS the public schema of that database. Never
 * point it at a real, shared or production database.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class WhatsAppMigrationPostgresTest {

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

    private void runAgain(String script) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/" + script));
        }
    }

    @Test
    void v15AndV16_createTheWhatsAppSchema_grantPermissionsLikeEmail_andAreSafeToRerun() throws Exception {

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }

        Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .baselineVersion("1")
                .baselineOnMigrate(true)
                .target("16")
                .load()
                .migrate();

        assertThat(scalar("SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("16");

        // ---- permissions: WHATSAPP_SEND mirrors EMAIL_SEND; management is ADMIN only
        assertThat(scope("ADMIN", "WHATSAPP_SEND")).isEqualTo("ALL");
        assertThat(scope("MANAGER", "WHATSAPP_SEND")).isEqualTo("TEAM");
        assertThat(scope("EMPLOYEE", "WHATSAPP_SEND")).isEqualTo("OWN");
        assertThat(scope("ADMIN", "WHATSAPP_TEMPLATE_MANAGE")).isEqualTo("ALL");
        assertThat(scope("ADMIN", "WHATSAPP_CONSENT_MANAGE")).isEqualTo("ALL");
        assertThat(count("MANAGER", "WHATSAPP_TEMPLATE_MANAGE")).isEqualTo("0");
        assertThat(count("EMPLOYEE", "WHATSAPP_CONSENT_MANAGE")).isEqualTo("0");

        // ---- nothing is opted in
        assertThat(scalar("SELECT count(*) FROM whatsapp_consent")).isEqualTo("0");

        // ---- consent: one row per number, only the two states
        exec("INSERT INTO whatsapp_consent (phone_e164, status, source, consented_at, created_at, updated_at) "
                + "VALUES ('919876500001','OPTED_IN','FORM',now(),now(),now())");
        assertThatThrownBy(() -> exec("INSERT INTO whatsapp_consent (phone_e164, status, source, consented_at, created_at, updated_at) "
                + "VALUES ('919876500001','OPTED_OUT','STOP',now(),now(),now())"))
                .hasMessageContaining("uq_whatsapp_consent_phone");
        assertThatThrownBy(() -> exec("INSERT INTO whatsapp_consent (phone_e164, status, source, consented_at, created_at, updated_at) "
                + "VALUES ('919876500002','MAYBE','FORM',now(),now(),now())"))
                .hasMessageContaining("chk_whatsapp_consent_status");

        // ---- send log: one claim per (batch, record); provider ids unique when present
        String row = "(batch_id, target, target_id, phone_e164, status, provider_message_id, created_at, updated_at) VALUES ";
        exec("INSERT INTO whatsapp_send_log " + row + "('b-0001','LEAD',7,'919876500001','PENDING',NULL,now(),now())");
        assertThatThrownBy(() -> exec("INSERT INTO whatsapp_send_log " + row
                + "('b-0001','LEAD',7,'919876500001','PENDING',NULL,now(),now())"))
                .hasMessageContaining("uq_whatsapp_send_log_batch_record");

        // many rows without a provider id are fine, a repeated provider id is not
        exec("INSERT INTO whatsapp_send_log " + row + "('b-0001','LEAD',8,'919876500002','FAILED',NULL,now(),now())");
        exec("INSERT INTO whatsapp_send_log " + row + "('b-0001','LEAD',9,'919876500003','ACCEPTED','m-1',now(),now())");
        assertThatThrownBy(() -> exec("INSERT INTO whatsapp_send_log " + row
                + "('b-0002','LEAD',9,'919876500003','ACCEPTED','m-1',now(),now())"))
                .hasMessageContaining("uq_whatsapp_send_log_provider_message");

        assertThatThrownBy(() -> exec("INSERT INTO whatsapp_send_log " + row
                + "('b-0003','LEAD',1,'919876500004','DELIVERED',NULL,now(),now())"))
                .hasMessageContaining("chk_whatsapp_send_log_status");
        assertThatThrownBy(() -> exec("UPDATE whatsapp_send_log SET delivery_status = 'SEEN' WHERE provider_message_id = 'm-1'"))
                .hasMessageContaining("chk_whatsapp_send_log_delivery");

        // ---- webhook events are idempotent by key
        exec("INSERT INTO whatsapp_webhook_event (event_key, event_type, received_at) VALUES ('ack:m-1:3','message.ack',now())");
        assertThatThrownBy(() -> exec("INSERT INTO whatsapp_webhook_event (event_key, event_type, received_at) "
                + "VALUES ('ack:m-1:3','message.ack',now())"))
                .hasMessageContaining("uq_whatsapp_webhook_event_key");

        // ---- re-running both migrations changes nothing and duplicates no grant
        runAgain("V15__whatsapp_templates_consent_and_permissions.sql");
        runAgain("V16__whatsapp_bulk_batches_send_log_and_webhook_events.sql");

        assertThat(scalar("SELECT count(*) FROM permissions WHERE code LIKE 'WHATSAPP_%'")).isEqualTo("3");
        assertThat(scalar("SELECT count(*) FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id "
                + "WHERE p.code LIKE 'WHATSAPP_%'")).isEqualTo("5");
        assertThat(scalar("SELECT count(*) FROM whatsapp_consent")).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM whatsapp_send_log")).isEqualTo("3");

        // ---- the email tables are untouched
        assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_name = 'email_send_log'")).isEqualTo("1");
    }

    private String scope(String role, String code) throws SQLException {
        return scalar("SELECT rp.scope FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                + "JOIN permissions p ON p.id = rp.permission_id WHERE r.name = '" + role + "' AND p.code = '" + code + "'");
    }

    private String count(String role, String code) throws SQLException {
        return scalar("SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                + "JOIN permissions p ON p.id = rp.permission_id WHERE r.name = '" + role + "' AND p.code = '" + code + "'");
    }
}
