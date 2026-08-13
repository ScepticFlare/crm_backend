package com.compact.crm.config;

import com.compact.crm.enums.LeadStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// Hibernate's ddl-auto=update only ever adds missing tables/columns - it
// never alters an existing CHECK constraint. On Postgres, @Enumerated(STRING)
// columns get an auto-generated CHECK constraint enumerating the LeadStatus
// constants that existed at the moment the column was first created, so
// every time a new LeadStatus value is added (e.g. INVALID), production
// writes using it are rejected with "violates check constraint
// leads_lead_status_check" until that constraint is refreshed by hand. This
// keeps it in sync with the current LeadStatus enum on every boot, without
// touching any existing lead rows. A failed sync fails startup on purpose:
// a boot that silently leaves the constraint stale would deploy
// "successfully" while still rejecting the exact LeadStatus values this
// fix exists to allow.
@Component
@RequiredArgsConstructor
public class LeadStatusConstraintSync implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LeadStatusConstraintSync.class);

    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {

        try (Connection connection = dataSource.getConnection()) {

            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                return;
            }

            List<String> existingConstraints = new ArrayList<>();

            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT cc.constraint_name
                    FROM information_schema.check_constraints cc
                    JOIN information_schema.constraint_column_usage ccu
                        ON cc.constraint_name = ccu.constraint_name
                        AND cc.constraint_schema = ccu.constraint_schema
                    WHERE ccu.table_name = 'leads'
                    AND ccu.column_name = 'lead_status'
                    """)) {

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existingConstraints.add(rs.getString("constraint_name"));
                    }
                }
            }

            String allowedValues = Arrays.stream(LeadStatus.values())
                    .map(status -> "'" + status.name() + "'")
                    .collect(Collectors.joining(", "));

            try (Statement statement = connection.createStatement()) {

                for (String constraintName : existingConstraints) {
                    statement.execute(
                            "ALTER TABLE leads DROP CONSTRAINT \"" + constraintName + "\""
                    );
                }

                statement.execute(
                        "ALTER TABLE leads ADD CONSTRAINT leads_lead_status_check " +
                                "CHECK (lead_status IN (" + allowedValues + "))"
                );
            }

            log.info("leads_lead_status_check constraint synced with LeadStatus enum ({})", allowedValues);

        } catch (Exception e) {

            log.error("Failed to sync leads_lead_status_check constraint with the LeadStatus enum. " +
                    "Failing startup so this is not silently left stale.", e);

            throw e;
        }
    }
}
