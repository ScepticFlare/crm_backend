package com.compact.crm.service;

import java.util.Locale;
import java.util.regex.Pattern;

// One definition of "a valid, normalized email address", shared by bulk email,
// the single-send path and the suppression list, so de-duplication and
// suppression always compare exactly the same string.
public final class EmailAddresses {

    // Deliberately simple and strict: one address, no spaces/commas/semicolons.
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+'-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    public static final int MAX_LENGTH = 254;

    private EmailAddresses() {
    }

    // Trimmed and lower-case; null / blank -> null.
    public static String normalize(String email) {

        if (email == null) {
            return null;
        }

        String trimmed = email.trim();

        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String email) {
        return email != null && email.length() <= MAX_LENGTH && EMAIL.matcher(email.trim()).matches();
    }
}
