package com.compact.crm.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// Every employee - existing or newly created, whatever role - must be able to
// connect their OWN mailbox on the Email Account page. That page and its API
// are gated only by EMAIL_SEND, which comes from the employee's role (there
// are no per-user grants). The H2 test context does not run Flyway, so this
// reads the real migrations and pins the grant for each seeded role.
class EmailSendForEveryRoleMigrationTest {

    private static Map<String, String> migrations() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            out.put(r.getFilename(), new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(out).isNotEmpty();
        return out;
    }

    @Test
    void everySeededRole_isGrantedEmailSend() throws IOException {

        Map<String, String> sql = migrations();

        // the roles the migrations create (V2: ADMIN, MANAGER, EMPLOYEE)
        String all = String.join("\n", sql.values());
        Matcher roles = Pattern.compile("INSERT INTO roles \\(name, role_rank\\) VALUES([^;]+);").matcher(all);
        assertThat(roles.find()).as("roles seed").isTrue();
        Matcher names = Pattern.compile("\\('([A-Z_]+)'").matcher(roles.group(1));
        java.util.List<String> seeded = new java.util.ArrayList<>();
        while (names.find()) {
            seeded.add(names.group(1));
        }
        assertThat(seeded).containsExactlyInAnyOrder("ADMIN", "MANAGER", "EMPLOYEE");

        // each one gets an EMAIL_SEND row (V6), in an INSERT INTO role_permissions statement
        String v6 = sql.entrySet().stream().filter(e -> e.getKey().startsWith("V6__")).findFirst().orElseThrow().getValue();
        for (String role : seeded) {
            Pattern grant = Pattern.compile("INSERT INTO role_permissions[^;]*r\\.name = '" + role
                    + "'[^;]*'EMAIL_SEND'[^;]*;", Pattern.DOTALL);
            assertThat(grant.matcher(v6).find()).as("EMAIL_SEND granted to " + role).isTrue();
        }
    }

    @Test
    void noMigration_revokesARolePermissionOrRemovesARole() throws IOException {
        migrations().forEach((file, sql) -> {
            String s = sql.toUpperCase(Locale.ROOT);
            assertThat(s).as(file).doesNotContain("DELETE FROM ROLE_PERMISSIONS").doesNotContain("DELETE FROM ROLES");
        });
    }
}
