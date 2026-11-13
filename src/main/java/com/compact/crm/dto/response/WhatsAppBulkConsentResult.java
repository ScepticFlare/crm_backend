package com.compact.crm.dto.response;

import java.util.List;

// Outcome of an admin bulk opt-in import, one row per submitted value (in
// order). outcome: OPTED_IN | ALREADY_OPTED_IN | SKIPPED_OPTED_OUT |
// INVALID_PHONE | DUPLICATE_IN_LIST.
public record WhatsAppBulkConsentResult(
        String importReference,
        int optedIn,
        int alreadyOptedIn,
        int skippedOptedOut,
        int invalid,
        int duplicates,
        List<Row> rows
) {

    public record Row(String input, String phoneMasked, String outcome) {
    }
}
