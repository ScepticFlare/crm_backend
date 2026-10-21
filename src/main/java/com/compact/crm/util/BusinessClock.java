package com.compact.crm.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

// "Today" for workflow rules that compare a plain calendar date (expected
// closing date, postponed review date) against the current day. The server
// JVM zone (typically UTC on Render) would flip "today" at 05:30 IST, so
// these rules use an explicit business zone instead. Overridable with the
// CRM_BUSINESS_TIMEZONE environment variable; defaults to Asia/Kolkata.
public final class BusinessClock {

    private static final ZoneId ZONE = resolveZone();

    private BusinessClock() {
    }

    private static ZoneId resolveZone() {

        String configured = System.getenv("CRM_BUSINESS_TIMEZONE");

        if (configured != null && !configured.isBlank()) {

            try {
                return ZoneId.of(configured.trim());
            } catch (Exception ignored) {
                // fall through to the default rather than failing startup
            }
        }

        return ZoneId.of("Asia/Kolkata");
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDateTime startOfToday() {
        return today().atStartOfDay();
    }
}
