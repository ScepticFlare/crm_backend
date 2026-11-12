package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretScrubberTest {

    private final SecretScrubber scrubber = new SecretScrubber("hunter22", "aws-0-region.pooler.example.com", "postgres.projectref", "crm");

    @Test
    void removesHostIpUserAndPasswordFromATypicalLibpqError() {

        String stderr = "pg_dump: error: connection to server at \"aws-0-region.pooler.example.com\" (52.1.2.3), port 5432 failed: "
                + "FATAL:  password authentication failed for user \"postgres.projectref\"\n";

        String out = scrubber.scrub(stderr);

        assertThat(out).doesNotContain("aws-0-region").doesNotContain("52.1.2.3").doesNotContain("projectref");
        assertThat(out).contains("password authentication failed").contains("[redacted]");
        assertThat(out).doesNotContain("\n");
    }

    @Test
    void removesUrisKeyValueSecretsAndIpv6Addresses() {

        String out = scrubber.scrub("could not connect: postgresql://u:hunter22@h:5432/db password=abc123 host=x.y "
                + "at 2600:1f18:abcd:1234::5 and (hunter22)");

        assertThat(out).doesNotContain("hunter22").doesNotContain("abc123").doesNotContain("x.y")
                .doesNotContain("2600:1f18").doesNotContain("postgresql://");
    }

    @Test
    void capsTheLength_andHandlesEmptyInput() {

        assertThat(scrubber.scrub("x".repeat(5000))).hasSize(SecretScrubber.MAX_LENGTH);
        assertThat(scrubber.scrub(null)).isEmpty();
        assertThat(scrubber.scrub("   ")).isEmpty();
    }

    @Test
    void keepsTheUsefulPartOfAVersionMismatchError() {

        String out = scrubber.scrub("pg_dump: error: aborting because of server version mismatch\n"
                + "pg_dump: detail: server version: 17.4; pg_dump version: 16.10");

        assertThat(out).contains("server version mismatch").contains("17.4").contains("16.10");
    }
}
