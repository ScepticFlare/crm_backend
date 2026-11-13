package com.compact.crm.service;

import java.util.Optional;
import java.util.regex.Pattern;

// Turns a phone number as stored on a Lead / Opportunity into the digits-only
// international form a WhatsApp chat id needs (e.g. "919876543210"), or
// rejects it. Strict on purpose - a wrong guess messages a stranger:
//
//   * Lead phones are entered as exactly 10 digits without a country code
//     (LeadRequest / PublicLeadRequest); imports strip "+91"; some historical
//     alternate numbers are landlines (e.g. 0431...). Only the configured
//     default country is supported in this MVP (India, "91").
//   * Accepted for country 91 (national mobile = 10 digits starting 6-9):
//       9876543210            -> 919876543210
//       +91 98765 43210       -> 919876543210
//       91-9876543210         -> 919876543210
//       09876543210           -> 919876543210  (domestic trunk 0 + mobile)
//   * Rejected: blank, letters, more than one number ("98..., 97..."),
//     landlines, too short / too long, other countries ("+44 ..."), and any
//     form that would need a digit to be guessed. A country code is never
//     added twice.
//
// For any other configured country code only "+<cc><national>" or
// "<cc><national>" with a 6-12 digit national part not starting with 0 is
// accepted, since no mobile-number rules are known for it.
public final class WhatsAppNumbers {

    // Formatting characters people type between digits. Anything else (letters,
    // commas, slashes...) makes the value ambiguous and it is rejected.
    private static final Pattern ALLOWED = Pattern.compile("^\\+?[0-9 ()\\-.]+$");

    private static final Pattern INDIA_MOBILE = Pattern.compile("^[6-9][0-9]{9}$");

    private WhatsAppNumbers() {
    }

    public static Optional<String> normalize(String raw, String defaultCountryCode) {

        if (raw == null || defaultCountryCode == null || !defaultCountryCode.matches("^[1-9][0-9]{0,3}$")) {
            return Optional.empty();
        }

        String trimmed = raw.strip();

        if (trimmed.isEmpty() || !ALLOWED.matcher(trimmed).matches()) {
            return Optional.empty();
        }

        boolean plus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("[^0-9]", "");

        String cc = defaultCountryCode;
        String national;

        if (plus) {
            // An explicit international number must be in the configured country.
            if (!digits.startsWith(cc)) {
                return Optional.empty();
            }
            national = digits.substring(cc.length());
        } else if ("91".equals(cc)) {
            national = indiaNational(digits);
            if (national == null) {
                return Optional.empty();
            }
        } else if (digits.startsWith(cc) && digits.length() > cc.length() + 5) {
            national = digits.substring(cc.length());
        } else {
            national = digits;
        }

        if ("91".equals(cc)) {
            return INDIA_MOBILE.matcher(national).matches() ? Optional.of(cc + national) : Optional.empty();
        }

        if (national.length() < 6 || national.length() > 12 || national.startsWith("0")
                || cc.length() + national.length() > 15) {
            return Optional.empty();
        }

        return Optional.of(cc + national);
    }

    // The 10-digit national part of an India number written without "+", or
    // null when the form is not one of the unambiguous ones.
    private static String indiaNational(String digits) {

        return switch (digits.length()) {
            case 10 -> digits;
            case 11 -> digits.startsWith("0") ? digits.substring(1) : null;
            case 12 -> digits.startsWith("91") ? digits.substring(2) : null;
            default -> null;
        };
    }

    // For screens and logs: country code + the last four digits only,
    // e.g. "+91 ******3210". Never the whole number.
    public static String mask(String normalized, String countryCode) {

        if (normalized == null || normalized.length() < 5) {
            return null;
        }

        String cc = countryCode != null && normalized.startsWith(countryCode) ? countryCode : "";
        String rest = normalized.substring(cc.length());
        String last4 = rest.substring(Math.max(0, rest.length() - 4));

        return (cc.isEmpty() ? "+" : "+" + cc + " ") + "*".repeat(Math.max(0, rest.length() - 4)) + last4;
    }
}
