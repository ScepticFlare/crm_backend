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
 * V14 (Opportunity-owned contact details, products and batteries) on a real
 * PostgreSQL, from a V13 database holding existing data. Same gating and
 * warning as BulkEmailMigrationPostgresTest: runs only when CRM_TEST_PG_URL is
 * set, and DROPS the public schema of that database.
 *
 * Checks that the migration is additive and only COPIES existing values:
 * every existing Opportunity gets exactly its Lead's contact, products and
 * batteries (including the battery the old screens failed to show), an
 * Opportunity whose Lead has no battery gets none (nothing invented), Lead
 * rows are untouched, and re-running the script changes nothing.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class OpportunityDetailsMigrationPostgresTest {

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
    void v14_copiesEachOpportunitysLeadValues_withoutInventingOrTouchingLeads_andIsRepeatable() throws Exception {

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }

        flyway("13").migrate();

        exec("INSERT INTO employees (id, name, email, phone, password) VALUES (900, 'Mig', 'mig@x.test', '9999900000', 'x')");
        exec("INSERT INTO products (id, name) VALUES (901, 'UPS'), (902, 'Inverter')");
        exec("INSERT INTO batteries (id, name) VALUES (903, 'SMF')");

        // Lead 1: product x2, inverter x1, battery x4 -> converted.
        exec("INSERT INTO leads (id, company_name, contact_person, phone, email, assigned_employee_id) "
                + "VALUES (910, 'Has All', 'Ravi', '9812345678', 'ravi@all.test', 900)");
        exec("INSERT INTO lead_products (lead_id, product_id, quantity) VALUES (910, 901, 2), (910, 902, 1)");
        exec("INSERT INTO lead_batteries (lead_id, battery_id, quantity) VALUES (910, 903, 4)");
        exec("INSERT INTO opportunities (id, title, lead_id) VALUES (920, 'Opp all', 910)");

        // Lead 2: product only, no battery, no email -> converted.
        exec("INSERT INTO leads (id, company_name, contact_person, phone, assigned_employee_id) "
                + "VALUES (911, 'No Battery', 'Asha', '9800000000', 900)");
        exec("INSERT INTO lead_products (lead_id, product_id, quantity) VALUES (911, 901, 1)");
        exec("INSERT INTO opportunities (id, title, lead_id) VALUES (921, 'Opp no battery', 911)");

        String leadProductsBefore = scalar("SELECT count(*) FROM lead_products");
        String leadBatteriesBefore = scalar("SELECT count(*) FROM lead_batteries");

        assertThat(flyway("14").migrate().migrationsExecuted).isEqualTo(1);

        // contact copied, NULL stays NULL
        assertThat(scalar("SELECT contact_person || '|' || contact_phone || '|' || contact_email "
                + "FROM opportunities WHERE id = 920")).isEqualTo("Ravi|9812345678|ravi@all.test");
        assertThat(scalar("SELECT contact_email IS NULL FROM opportunities WHERE id = 921")).isEqualTo("t");

        // line items copied exactly, battery included
        assertThat(scalar("SELECT string_agg(product_id || 'x' || quantity, ',' ORDER BY product_id) "
                + "FROM opportunity_products WHERE opportunity_id = 920")).isEqualTo("901x2,902x1");
        assertThat(scalar("SELECT string_agg(battery_id || 'x' || quantity, ',') "
                + "FROM opportunity_batteries WHERE opportunity_id = 920")).isEqualTo("903x4");

        // no battery on the lead -> none invented
        assertThat(scalar("SELECT count(*) FROM opportunity_batteries WHERE opportunity_id = 921")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM opportunity_products WHERE opportunity_id = 921")).isEqualTo("1");

        // leads untouched
        assertThat(scalar("SELECT count(*) FROM lead_products")).isEqualTo(leadProductsBefore);
        assertThat(scalar("SELECT count(*) FROM lead_batteries")).isEqualTo(leadBatteriesBefore);

        // an edit made after the migration is never overwritten by a re-run
        exec("UPDATE opportunities SET contact_person = 'Edited' WHERE id = 920");
        exec("DELETE FROM opportunity_products WHERE opportunity_id = 920 AND product_id = 902");

        try (Connection c = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/V14__opportunity_own_contact_products_and_batteries.sql"));
        }

        assertThat(scalar("SELECT contact_person FROM opportunities WHERE id = 920")).isEqualTo("Edited");
        assertThat(scalar("SELECT count(*) FROM opportunity_products WHERE opportunity_id = 920")).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM opportunity_batteries")).isEqualTo("1");

        // deleting an opportunity removes its own line items with it
        exec("DELETE FROM opportunities WHERE id = 921");
        assertThat(scalar("SELECT count(*) FROM opportunity_products WHERE opportunity_id = 921")).isEqualTo("0");
    }
}
