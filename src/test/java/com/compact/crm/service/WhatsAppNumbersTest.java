package com.compact.crm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppNumbersTest {

    private static Optional<String> n(String raw) {
        return WhatsAppNumbers.normalize(raw, "91");
    }

    @Test
    void tenDigitIndianMobile_getsTheCountryCodeOnce() {
        assertThat(n("9876543210")).contains("919876543210");
        assertThat(n("6000000001")).contains("916000000001");
    }

    @Test
    void existingCountryCodeIsNeverAddedTwice() {
        assertThat(n("+919876543210")).contains("919876543210");
        assertThat(n("+91 98765 43210")).contains("919876543210");
        assertThat(n("919876543210")).contains("919876543210");
        assertThat(n("91-98765-43210")).contains("919876543210");
        assertThat(n("+91 (98765) 43210")).contains("919876543210");
        assertThat(n("98765.43210")).contains("919876543210");
    }

    @Test
    void domesticTrunkZeroBeforeAMobileIsDropped() {
        assertThat(n("09876543210")).contains("919876543210");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                     // blank
            "   ",
            "98765",                // too short
            "98765432101234",       // too long
            "5876543210",           // not a mobile prefix (5)
            "04314021154",          // landline (historical alternate phone)
            "0431402115",           // landline, 10 digits starting 0
            "+44 7911 123456",      // another country
            "+9109876543210",       // ambiguous: +91 plus trunk 0
            "9876543210, 9876500000", // two numbers
            "9876543210 / 9876500000",
            "98765abc10",           // letters
            "+",
            "00919876543210"        // international dialling prefix - not guessed
    })
    void rejectsAnythingThatWouldNeedAGuess(String raw) {
        assertThat(n(raw)).isEmpty();
    }

    @Test
    void nullAndBadConfigurationAreRejected() {
        assertThat(n(null)).isEmpty();
        assertThat(WhatsAppNumbers.normalize("9876543210", null)).isEmpty();
        assertThat(WhatsAppNumbers.normalize("9876543210", "+91")).isEmpty();
        assertThat(WhatsAppNumbers.normalize("9876543210", "0")).isEmpty();
    }

    @Test
    void otherConfiguredCountry_onlyAcceptsItsOwnPrefixWithoutGuessingMobileRules() {
        assertThat(WhatsAppNumbers.normalize("+44 7911 123456", "44")).contains("447911123456");
        assertThat(WhatsAppNumbers.normalize("7911123456", "44")).contains("447911123456");
        assertThat(WhatsAppNumbers.normalize("+91 98765 43210", "44")).isEmpty();
        assertThat(WhatsAppNumbers.normalize("07911123456", "44")).isEmpty();
    }

    @Test
    void maskShowsOnlyTheCountryCodeAndLastFourDigits() {
        assertThat(WhatsAppNumbers.mask("919876543210", "91")).isEqualTo("+91 ******3210");
        assertThat(WhatsAppNumbers.mask("919876543210", "91")).doesNotContain("98765");
        assertThat(WhatsAppNumbers.mask(null, "91")).isNull();
    }
}
