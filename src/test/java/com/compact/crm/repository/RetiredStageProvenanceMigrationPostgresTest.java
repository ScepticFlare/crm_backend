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

/**
 * V19 (retired-stage provenance columns) on a real PostgreSQL, from a V18
 * database holding existing data in the stages that are being retired
 * (UNRESPONSIVE, INVALID, a custom stage). Same gating and warning as the
 * other *MigrationPostgresTest classes: runs only when CRM_TEST_PG_URL is
 * set, and DROPS the public schema of that database - point it at a
 * disposable one.
 *
 * Checks that V19 is purely additive: it adds three nullable columns, every
 * existing Opportunity keeps every value (and gets NULL provenance - the
 * startup retirement does the move, by stage type), no stage row is touched,
 * and re-running it is harmless.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class RetiredStageProvenanceMigrationPostgresTest {

    private static final String URL = System.getenv("CRM_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("CRM_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", "");

    private static final String OPPORTUNITY_SNAPSHOT = "SELECT string_agg(id || '|' || title || '|' || product_value "
            + "|| '|' || expected_closing_date || '|' || sales_stage_id || '|' || lead_id || '|' || contact_person "
            + "|| '|' || coalesce(sub_status, '-') || '|' || coalesce(lost_reason, '-') || '|' || updated_at, ';' "
            + "ORDER BY id) FROM opportunities";

    private static final String STAGE_SNAPSHOT = "SELECT string_agg(id || '|' || name || '|' "
            + "|| coalesce(stage_type, '-') || '|' || is_active, ';' ORDER BY id) FROM sales_stages";

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

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .baselineVersion("1")
                .baselineOnMigrate(true)
                .target(target)
                .load();
    }

    @Test
    void v19_addsNullableProvenanceColumns_withoutTouchingAnyExistingRow_andIsRepeatable() throws Exception {

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }

        flyway("18").migrate();

        // employees.role_id is NOT NULL since V2 (roles seeded by V2)
        exec("INSERT INTO employees (id, name, email, phone, password, role_id) "
                + "SELECT 900, 'Mig', 'mig@x.test', '9999900000', 'x', id FROM roles WHERE name = 'EMPLOYEE'");
        exec("INSERT INTO sales_stages (id, name, stage_type, is_active) VALUES "
                + "(940, 'UNRESPONSIVE', 'LEGACY_UNRESPONSIVE', true), (941, 'INVALID', 'LEGACY_INVALID', true), "
                + "(942, 'Site Survey', NULL, true), (943, 'DROPPED', 'LEGACY_DROPPED', true)");
        exec("INSERT INTO leads (id, company_name, contact_person, phone, assigned_employee_id) VALUES "
                + "(910, 'Quiet Co', 'Ravi', '9812345678', 900), (911, 'Bogus Co', 'Asha', '9800000000', 900), "
                + "(912, 'Survey Co', 'Meera', '9800000001', 900)");
        exec("INSERT INTO opportunities (id, title, product_value, expected_closing_date, sales_stage_id, lead_id, "
                + "contact_person, lost_reason, updated_at) VALUES "
                + "(920, 'Unresponsive', 1500.5, DATE '2027-01-15', 940, 910, 'Ravi', NULL, TIMESTAMP '2026-10-01 10:00:00'), "
                + "(921, 'Invalid', 99, DATE '2027-02-01', 941, 911, 'Asha', 'spam', TIMESTAMP '2026-10-02 11:00:00'), "
                + "(922, 'Custom', 7, DATE '2027-03-01', 942, 912, 'Meera', NULL, TIMESTAMP '2026-10-03 12:00:00')");

        String before = scalar(OPPORTUNITY_SNAPSHOT);
        String stagesBefore = scalar(STAGE_SNAPSHOT);

        assertThat(flyway("19").migrate().migrationsExecuted).isEqualTo(1);

        for (String column : new String[]{"retired_stage_type", "retired_stage_name", "retired_stage_moved_at"}) {
            assertThat(scalar("SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_name = 'opportunities' AND column_name = '" + column + "'")).as(column).isEqualTo("YES");
        }

        assertThat(scalar("SELECT count(*) FROM opportunities WHERE retired_stage_type IS NOT NULL "
                + "OR retired_stage_name IS NOT NULL OR retired_stage_moved_at IS NOT NULL")).isEqualTo("0");
        assertThat(scalar(OPPORTUNITY_SNAPSHOT)).isEqualTo(before);
        assertThat(scalar(STAGE_SNAPSHOT)).isEqualTo(stagesBefore);

        // the columns hold what the startup retirement writes
        exec("UPDATE opportunities SET sales_stage_id = 943, retired_stage_type = 'LEGACY_UNRESPONSIVE', "
                + "retired_stage_name = 'UNRESPONSIVE', retired_stage_moved_at = TIMESTAMP '2026-11-13 09:00:00' "
                + "WHERE id = 920");

        // re-running the script is harmless (IF NOT EXISTS) and keeps the values
        try (Connection c = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/V19__opportunity_retired_stage_provenance.sql"));
        }

        assertThat(scalar("SELECT retired_stage_type || '|' || retired_stage_name || '|' || retired_stage_moved_at "
                + "FROM opportunities WHERE id = 920")).isEqualTo("LEGACY_UNRESPONSIVE|UNRESPONSIVE|2026-11-13 09:00:00");
        assertThat(scalar("SELECT retired_stage_moved_at IS NULL FROM opportunities WHERE id = 921")).isEqualTo("t");
    }
}
