package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresTargetTest {

    @Test
    void parsesThePoolerStyleUrl_withTheDatasourceCredentials() {

        PostgresTarget t = PostgresTarget.fromJdbc(
                "jdbc:postgresql://aws-0-region.pooler.example.com:5432/postgres?sslmode=require&prepareThreshold=0",
                "postgres.projectref", "s3cret!");

        assertThat(t.host()).isEqualTo("aws-0-region.pooler.example.com");
        assertThat(t.port()).isEqualTo(5432);
        assertThat(t.database()).isEqualTo("postgres");
        assertThat(t.username()).isEqualTo("postgres.projectref");
        assertThat(t.password()).isEqualTo("s3cret!");
        assertThat(t.urlSslMode()).isEqualTo("require");
        assertThat(t.isLocal()).isFalse();
    }

    @Test
    void defaultsThePort_andFallsBackToCredentialsInTheUrl() {

        PostgresTarget t = PostgresTarget.fromJdbc("jdbc:postgresql://localhost/crm?user=crm_user&password=p%40ss", "", "");

        assertThat(t.port()).isEqualTo(5432);
        assertThat(t.username()).isEqualTo("crm_user");
        assertThat(t.password()).isEqualTo("p@ss");
        assertThat(t.isLocal()).isTrue();
    }

    @Test
    void acceptsAnIpv6Literal() {

        PostgresTarget t = PostgresTarget.fromJdbc("jdbc:postgresql://[::1]:6543/crm", "u", "p");

        assertThat(t.host()).isEqualTo("::1");
        assertThat(t.port()).isEqualTo(6543);
    }

    @Test
    void refusesAnythingButAPlainSingleHostPostgresUrl() {

        assertThatThrownBy(() -> PostgresTarget.fromJdbc("jdbc:h2:mem:crm_test", "sa", ""))
                .isInstanceOf(BackupFailedException.class).hasMessageContaining("not a PostgreSQL database");
        assertThatThrownBy(() -> PostgresTarget.fromJdbc(null, "sa", "")).isInstanceOf(BackupFailedException.class);

        for (String bad : new String[]{
                "jdbc:postgresql://h1:5432,h2:5432/crm",          // several hosts
                "jdbc:postgresql://host:99999/crm",               // bad port
                "jdbc:postgresql://host:abc/crm",
                "jdbc:postgresql://host",                         // no database
                "jdbc:postgresql://host/crm db",                  // odd database name
                "jdbc:postgresql://host/-crm%3Dx",
                "jdbc:postgresql://us er@host/crm"}) {
            assertThatThrownBy(() -> PostgresTarget.fromJdbc(bad, "user", "pw"))
                    .as(bad).isInstanceOf(BackupFailedException.class);
        }

        assertThatThrownBy(() -> PostgresTarget.fromJdbc("jdbc:postgresql://host/crm", "bad user;", "pw"))
                .isInstanceOf(BackupFailedException.class);
    }

    @Test
    void toString_neverShowsHostUserOrPassword() {

        PostgresTarget t = PostgresTarget.fromJdbc("jdbc:postgresql://db.secret-host.example:5432/crm", "admin_user", "hunter22");

        assertThat(t.toString()).doesNotContain("secret-host").doesNotContain("admin_user").doesNotContain("hunter22");
    }
}
