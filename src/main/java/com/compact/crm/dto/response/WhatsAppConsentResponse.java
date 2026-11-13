package com.compact.crm.dto.response;

import com.compact.crm.enums.WhatsAppConsentStatus;

import java.time.LocalDateTime;

// One number's consent state. `phone` (full number) is only filled for the
// admin consent screens; everyone else sees `phoneMasked` only.
// status null = no consent on record (never messaged in bulk).
// validPhone false = the record's phone cannot be used for WhatsApp at all.
// canRecordOptIn = the caller may record an opt-in now (false for a non-admin
// when the number has opted out).
public record WhatsAppConsentResponse(
        String phone,
        String phoneMasked,
        boolean validPhone,
        WhatsAppConsentStatus status,
        String source,
        String evidence,
        LocalDateTime consentedAt,
        LocalDateTime updatedAt,
        boolean canRecordOptIn,
        boolean canRecordOptOut
) {
}
