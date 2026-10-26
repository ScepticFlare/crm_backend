package com.compact.crm.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the REAL Flyway chain (V2..V11, exactly as production would: baseline at
 * version 1, then migrate) against a disposable PostgreSQL, on a representative
 * pre-V2 schema with legacy rows (src/test/resources/pg-baseline-v1.sql).
 *
 * Skipped unless CRM_TEST_PG_URL points at a throw-away database, e.g.
 *   CRM_TEST_PG_URL=jdbc:postgresql://localhost:54329/postgres
 * (CRM_TEST_PG_USER, default "postgres"; CRM_TEST_PG_PASSWORD, default empty).
 * The public schema of that database is DROPPED and recreated - never point
 * this at anything that matters, and never at production.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class ExportGrantMigrationPostgresTest {

    private static final String URL = System.getenv("CRM_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("CRM_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", "");

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(URL, USER, PASSWORD);
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

    // "ROLE:PERMISSION" -> scope
    private Map<String, String> grants() throws Exception {

        Map<String, String> out = new LinkedHashMap<>();

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT r.name, p.code, rp.scope FROM role_permissions rp "
                             + "JOIN roles r ON r.id = rp.role_id JOIN permissions p ON p.id = rp.permission_id "
                             + "ORDER BY r.name, p.code")) {
            while (rs.next()) {
                out.put(rs.getString(1) + ":" + rs.getString(2), rs.getString(3));
            }
        }

        return out;
    }

    private String scalar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void v11_givesEveryRoleLeadAndOpportunityExportAtItsViewScope_andNothingElseChanges() throws Exception {

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }

        // production state before this change: V2..V10 applied
        flyway("10").migrate();

        Map<String, String> before = grants();

        assertThat(before)
                .containsEntry("ADMIN:LEAD_EXPORT", "ALL")
                .doesNotContainKeys("EMPLOYEE:LEAD_EXPORT", "MANAGER:LEAD_EXPORT",
                        "EMPLOYEE:OPPORTUNITY_EXPORT", "MANAGER:OPPORTUNITY_EXPORT");

        String employeesBefore = scalar("SELECT string_agg(e.email || '=' || r.name, ',' ORDER BY e.email) "
                + "FROM employees e JOIN roles r ON r.id = e.role_id");
        String leadsBefore = scalar("SELECT count(*) || ':' || string_agg(lead_status, ',' ORDER BY id) FROM leads");

        // this change
        flyway("11").migrate();

        Map<String, String> after = grants();

        assertThat(after)
                .containsEntry("ADMIN:LEAD_EXPORT", "ALL")
                .containsEntry("ADMIN:OPPORTUNITY_EXPORT", "ALL")
                .containsEntry("MANAGER:LEAD_EXPORT", "TEAM")
                .containsEntry("MANAGER:OPPORTUNITY_EXPORT", "TEAM")
                .containsEntry("EMPLOYEE:LEAD_EXPORT", "OWN")
                .containsEntry("EMPLOYEE:OPPORTUNITY_EXPORT", "OWN")
                // export scope == the role's own view scope
                .containsEntry("EMPLOYEE:LEAD_VIEW", "OWN")
                .containsEntry("MANAGER:LEAD_VIEW", "TEAM");

        // exactly the four new rows; every other grant identical
        Map<String, String> added = new LinkedHashMap<>(after);
        before.keySet().forEach(added::remove);
        assertThat(added.keySet()).containsExactlyInAnyOrder(
                "MANAGER:LEAD_EXPORT", "MANAGER:OPPORTUNITY_EXPORT",
                "EMPLOYEE:LEAD_EXPORT", "EMPLOYEE:OPPORTUNITY_EXPORT");
        before.forEach((k, v) -> assertThat(after).containsEntry(k, v));

        // customer / follow-up export and every delete permission stay admin-only
        for (String code : new String[]{"CUSTOMER_EXPORT", "FOLLOWUP_EXPORT", "LEAD_DELETE",
                "OPPORTUNITY_DELETE", "CUSTOMER_DELETE", "FOLLOWUP_DELETE"}) {
            assertThat(after).containsEntry("ADMIN:" + code, "ALL")
                    .doesNotContainKeys("MANAGER:" + code, "EMPLOYEE:" + code);
        }

        // no role was created (in particular no MARKETING) and no employee / lead was touched
        assertThat(scalar("SELECT string_agg(name, ',' ORDER BY name) FROM roles")).isEqualTo("ADMIN,EMPLOYEE,MANAGER");
        assertThat(scalar("SELECT string_agg(e.email || '=' || r.name, ',' ORDER BY e.email) "
                + "FROM employees e JOIN roles r ON r.id = e.role_id")).isEqualTo(employeesBefore);
        assertThat(scalar("SELECT count(*) || ':' || string_agg(lead_status, ',' ORDER BY id) FROM leads"))
                .isEqualTo(leadsBefore);

        // re-running the migration SQL is a no-op
        try (Connection c = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/V11__grant_lead_opportunity_export_to_all_roles.sql"));
        }
        assertThat(grants()).isEqualTo(after);

        // a second migrate has nothing left to do
        assertThat(flyway("11").migrate().migrationsExecuted).isZero();
    }
}
