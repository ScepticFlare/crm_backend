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
 * V18 ("Previously New" provenance column) on a real PostgreSQL, from a V17
 * database holding existing data. Same gating and warning as the other
 * *MigrationPostgresTest classes: runs only when CRM_TEST_PG_URL is set, and
 * DROPS the public schema of that database - point it at a disposable one.
 *
 * Checks that V18 is purely additive: it adds a nullable column, every
 * existing Opportunity keeps every value (and gets NULL - nobody is marked
 * "Previously New" by the migration itself; the startup retirement does that
 * by stage type), no stage row is touched, and re-running it is harmless.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class PreviouslyNewMigrationPostgresTest {

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
    void v18_addsANullableProvenanceColumn_withoutTouchingAnyExistingRow_andIsRepeatable() throws Exception {

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }

        flyway("17").migrate();

        // employees.role_id is NOT NULL since V2 (roles seeded by V2)
        exec("INSERT INTO employees (id, name, email, phone, password, role_id) "
                + "SELECT 900, 'Mig', 'mig@x.test', '9999900000', 'x', id FROM roles WHERE name = 'EMPLOYEE'");
        exec("INSERT INTO sales_stages (id, name, stage_type, is_active) VALUES "
                + "(930, 'NEW', 'NEW', true), (931, 'IN PROGRESS', 'IN_PROGRESS', true)");
        exec("INSERT INTO leads (id, company_name, contact_person, phone, assigned_employee_id) "
                + "VALUES (910, 'Was New Co', 'Ravi', '9812345678', 900), (911, 'Always IP Co', 'Asha', '9800000000', 900)");
        exec("INSERT INTO opportunities (id, title, product_value, expected_closing_date, sales_stage_id, lead_id, "
                + "contact_person, updated_at) VALUES "
                + "(920, 'In NEW', 1500.5, DATE '2027-01-15', 930, 910, 'Ravi', TIMESTAMP '2026-10-01 10:00:00'), "
                + "(921, 'In IP', 99, DATE '2027-02-01', 931, 911, 'Asha', TIMESTAMP '2026-10-02 11:00:00')");

        String before = scalar("SELECT string_agg(id || '|' || title || '|' || product_value || '|' "
                + "|| expected_closing_date || '|' || sales_stage_id || '|' || lead_id || '|' || contact_person "
                + "|| '|' || updated_at, ';' ORDER BY id) FROM opportunities");
        String stagesBefore = scalar("SELECT string_agg(id || '|' || name || '|' || stage_type || '|' || is_active, ';' "
                + "ORDER BY id) FROM sales_stages");

        assertThat(flyway("18").migrate().migrationsExecuted).isEqualTo(1);

        assertThat(scalar("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'opportunities' AND column_name = 'migrated_from_new_at'")).isEqualTo("YES");
        assertThat(scalar("SELECT count(*) FROM opportunities WHERE migrated_from_new_at IS NOT NULL")).isEqualTo("0");

        String after = scalar("SELECT string_agg(id || '|' || title || '|' || product_value || '|' "
                + "|| expected_closing_date || '|' || sales_stage_id || '|' || lead_id || '|' || contact_person "
                + "|| '|' || updated_at, ';' ORDER BY id) FROM opportunities");
        assertThat(after).isEqualTo(before);
        assertThat(scalar("SELECT string_agg(id || '|' || name || '|' || stage_type || '|' || is_active, ';' "
                + "ORDER BY id) FROM sales_stages")).isEqualTo(stagesBefore);

        // re-running the script is harmless (IF NOT EXISTS) and keeps a value
        exec("UPDATE opportunities SET migrated_from_new_at = TIMESTAMP '2026-11-13 09:00:00' WHERE id = 920");

        try (Connection c = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/V18__opportunity_previously_new_provenance.sql"));
        }

        assertThat(scalar("SELECT migrated_from_new_at FROM opportunities WHERE id = 920"))
                .startsWith("2026-11-13 09:00:00");
        assertThat(scalar("SELECT migrated_from_new_at IS NULL FROM opportunities WHERE id = 921")).isEqualTo("t");
    }
}
