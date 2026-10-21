package com.compact.crm.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

// The stale cutoff is "now in the business timezone minus N months", N from
// crm.lead.stale-after-months (6 when unset or not positive).
class StaleLeadRulesTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of(
            System.getenv("CRM_BUSINESS_TIMEZONE") != null ? System.getenv("CRM_BUSINESS_TIMEZONE") : "Asia/Kolkata");

    private static long secondsFrom(LocalDateTime expected, LocalDateTime actual) {
        return Math.abs(Duration.between(expected, actual).getSeconds());
    }

    @Test
    void defaultsToSixMonths_inTheBusinessTimezone() {

        LocalDateTime expected = LocalDateTime.now(BUSINESS_ZONE).minusMonths(6);

        assertThat(secondsFrom(expected, StaleLeadRules.cutoff(6))).isLessThan(5);
        assertThat(secondsFrom(expected, StaleLeadRules.cutoff(0))).isLessThan(5);
        assertThat(secondsFrom(expected, StaleLeadRules.cutoff(-3))).isLessThan(5);
    }

    @Test
    void honoursTheConfiguredNumberOfMonths() {

        assertThat(secondsFrom(LocalDateTime.now(BUSINESS_ZONE).minusMonths(3), StaleLeadRules.cutoff(3)))
                .isLessThan(5);
        assertThat(secondsFrom(LocalDateTime.now(BUSINESS_ZONE).minusMonths(12), StaleLeadRules.cutoff(12)))
                .isLessThan(5);
    }
}
