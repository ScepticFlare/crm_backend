package com.compact.crm.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup stage retirements (NewStageRetirement, LegacyStageRetirement)
 * on a real PostgreSQL migrated by Flyway (V2..V19), with the real bulk
 * updates. Existing data is inserted by SQL the way an older installation
 * holds it. Same gating and warning as the other PostgreSQL tests:
 * CRM_TEST_PG_URL, and the public schema is DROPPED - point it at a
 * disposable database.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${CRM_TEST_PG_URL}",
        "spring.datasource.username=${CRM_TEST_PG_USER:postgres}",
        "spring.datasource.password=${CRM_TEST_PG_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=1",
        "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=true",
})
class StageRetirementPostgresTest {

    @BeforeAll
    static void freshBaseline() throws Exception {

        DriverManagerDataSource ds = new DriverManagerDataSource(
                System.getenv("CRM_TEST_PG_URL"),
                System.getenv().getOrDefault("CRM_TEST_PG_USER", "postgres"),
                System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", ""));

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NewStageRetirement newStageRetirement;
    @Autowired private LegacyStageRetirement legacyStageRetirement;

    private String one(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private String stageOf(long opportunityId) {
        return one("SELECT coalesce(s.stage_type, '-') || '|' || coalesce(o.sub_status, '-') FROM opportunities o "
                + "JOIN sales_stages s ON s.id = o.sales_stage_id WHERE o.id = " + opportunityId);
    }

    private String provenanceOf(long opportunityId) {
        return one("SELECT coalesce(retired_stage_type, '-') || '|' || coalesce(retired_stage_name, '-') || '|' "
                + "|| (retired_stage_moved_at IS NOT NULL) || '|' || (migrated_from_new_at IS NOT NULL) "
                + "FROM opportunities WHERE id = " + opportunityId);
    }

    @Test
    void everyOpportunityEndsInAMainStage_withProvenanceAndHistory_andNothingElseChanges() {

        // The app has started: Flyway ran and the seeder created the five main stages.
        assertThat(one("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")).isEqualTo("19");

        jdbc.execute("INSERT INTO employees (id, name, email, phone, password, role_id) "
                + "SELECT 9900, 'Ret', 'ret@pg.test', '9123400099', 'x', id FROM roles WHERE name = 'EMPLOYEE'");
        jdbc.execute("INSERT INTO sales_stages (id, name, stage_type, is_active) VALUES "
                + "(9930, 'NEW', 'NEW', true), "
                + "(9931, 'Gone Quiet', 'LEGACY_UNRESPONSIVE', true), "
                + "(9932, 'INVALID', 'LEGACY_INVALID', true), "
                + "(9933, 'NEGOTIATION', 'LEGACY_NEGOTIATION', true), "
                + "(9934, 'Quote Sent', NULL, true), "
                + "(9935, 'Site Survey', NULL, true), "
                // a duplicate of the seeded WON row, left untyped
                + "(9936, 'won', NULL, true)");

        // the last opportunity has no stage at all
        String[] stages = {"9930", "9931", "9932", "9933", "9934", "9935", "9936", "NULL"};

        for (int i = 0; i < stages.length; i++) {
            jdbc.execute("INSERT INTO leads (id, company_name, contact_person, phone, assigned_employee_id) VALUES ("
                    + (9910 + i) + ", 'Co " + i + "', 'P" + i + "', '98000000" + (10 + i) + "', 9900)");
            jdbc.execute("INSERT INTO opportunities (id, title, product_value, expected_closing_date, sales_stage_id, "
                    + "lead_id, contact_person, lost_reason, updated_at) VALUES (" + (9920 + i) + ", 'Deal " + i
                    + "', " + (100 + i) + ".5, DATE '2027-01-15', " + stages[i] + ", " + (9910 + i)
                    + ", 'P" + i + "', 'reason " + i + "', TIMESTAMP '2026-10-01 10:00:00')");
        }

        String untouched = "SELECT string_agg(id || '|' || title || '|' || product_value || '|' || expected_closing_date "
                + "|| '|' || lead_id || '|' || contact_person || '|' || lost_reason || '|' || updated_at, ';' ORDER BY id) "
                + "FROM opportunities WHERE id BETWEEN 9920 AND 9927";
        String before = one(untouched);

        assertThat(newStageRetirement.retireNewStage()).isEqualTo(1);
        assertThat(legacyStageRetirement.retireLegacyStages()).isEqualTo(7);

        assertThat(stageOf(9920)).isEqualTo("IN_PROGRESS|-");
        assertThat(provenanceOf(9920)).isEqualTo("-|-|false|true");

        assertThat(stageOf(9921)).isEqualTo("LEGACY_DROPPED|-");
        assertThat(provenanceOf(9921)).isEqualTo("LEGACY_UNRESPONSIVE|Gone Quiet|true|false");

        assertThat(stageOf(9922)).isEqualTo("LOST|-");
        assertThat(provenanceOf(9922)).isEqualTo("LEGACY_INVALID|INVALID|true|false");

        assertThat(stageOf(9923)).isEqualTo("IN_PROGRESS|NEGOTIATION");
        assertThat(provenanceOf(9923)).isEqualTo("LEGACY_NEGOTIATION|NEGOTIATION|true|false");

        assertThat(stageOf(9924)).isEqualTo("IN_PROGRESS|QUOTATION_SENT");
        assertThat(provenanceOf(9924)).isEqualTo("-|Quote Sent|true|false");

        assertThat(stageOf(9925)).isEqualTo("IN_PROGRESS|-");
        assertThat(provenanceOf(9925)).isEqualTo("-|Site Survey|true|false");

        assertThat(stageOf(9926)).isEqualTo("WON|-");
        assertThat(provenanceOf(9926)).isEqualTo("-|won|true|false");

        assertThat(stageOf(9927)).isEqualTo("IN_PROGRESS|-");
        assertThat(provenanceOf(9927)).isEqualTo("-|-|true|false");

        // every other column - including Last Modified - is unchanged
        assertThat(one(untouched)).isEqualTo(before);

        // history: one stage entry each, plus a sub-status entry where it was set
        assertThat(one("SELECT count(*) FROM activity_log WHERE field_name = 'salesStage' "
                + "AND entity_id BETWEEN 9920 AND 9927")).isEqualTo("8");
        assertThat(one("SELECT count(*) FROM activity_log WHERE field_name = 'subStatus' "
                + "AND entity_id BETWEEN 9920 AND 9927")).isEqualTo("2");
        assertThat(one("SELECT old_value FROM activity_log WHERE field_name = 'salesStage' AND entity_id = 9925"))
                .isEqualTo("Site Survey");

        // every retired row kept, inactive; no opportunity outside the five main stages
        assertThat(one("SELECT count(*) FROM sales_stages WHERE id BETWEEN 9930 AND 9936 AND is_active")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM opportunities o JOIN sales_stages s ON s.id = o.sales_stage_id "
                + "WHERE s.stage_type IS NULL OR s.stage_type NOT IN "
                + "('IN_PROGRESS','WON','LOST','POSTPONED','LEGACY_DROPPED')")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM opportunities WHERE sales_stage_id IS NULL")).isEqualTo("0");

        // a second startup changes nothing
        assertThat(newStageRetirement.retireNewStage()).isZero();
        assertThat(legacyStageRetirement.retireLegacyStages()).isZero();
        assertThat(one(untouched)).isEqualTo(before);
    }

    // Two instances starting at once: the row locks (SELECT ... FOR UPDATE)
    // make one wait for the other, so every opportunity is moved once and has
    // exactly one history entry.
    @Test
    void twoConcurrentStartups_moveEachOpportunityOnce_withExactlyOneHistoryEntry() throws Exception {

        jdbc.execute("INSERT INTO employees (id, name, email, phone, password, role_id) "
                + "SELECT 9901, 'Conc', 'conc@pg.test', '9123400098', 'x', id FROM roles WHERE name = 'EMPLOYEE'");
        jdbc.execute("INSERT INTO sales_stages (id, name, stage_type, is_active) VALUES "
                + "(9950, 'Concurrent A', NULL, true), (9951, 'Concurrent B', NULL, true)");

        for (int i = 0; i < 40; i++) {
            jdbc.execute("INSERT INTO leads (id, company_name, contact_person, phone, assigned_employee_id) VALUES ("
                    + (9800 + i) + ", 'Conc " + i + "', 'C', '9700000000', 9901)");
            jdbc.execute("INSERT INTO opportunities (id, title, sales_stage_id, lead_id) VALUES ("
                    + (9700 + i) + ", 'Conc " + i + "', " + (i % 2 == 0 ? 9950 : 9951) + ", " + (9800 + i) + ")");
        }

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);

        try {
            java.util.concurrent.Callable<Integer> run = () -> {
                start.await();
                return legacyStageRetirement.retireLegacyStages();
            };

            java.util.concurrent.Future<Integer> a = pool.submit(run);
            java.util.concurrent.Future<Integer> b = pool.submit(run);

            assertThat(a.get(60, java.util.concurrent.TimeUnit.SECONDS)
                    + b.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }

        assertThat(one("SELECT count(*) FROM opportunities o JOIN sales_stages s ON s.id = o.sales_stage_id "
                + "WHERE o.id BETWEEN 9700 AND 9739 AND s.stage_type = 'IN_PROGRESS'")).isEqualTo("40");
        assertThat(one("SELECT count(*) FROM activity_log WHERE field_name = 'salesStage' "
                + "AND entity_id BETWEEN 9700 AND 9739")).isEqualTo("40");
        assertThat(one("SELECT count(DISTINCT entity_id) FROM activity_log WHERE field_name = 'salesStage' "
                + "AND entity_id BETWEEN 9700 AND 9739")).isEqualTo("40");
    }
}
