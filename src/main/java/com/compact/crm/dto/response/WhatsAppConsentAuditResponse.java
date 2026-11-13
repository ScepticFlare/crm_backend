package com.compact.crm.dto.response;

import com.compact.crm.enums.WhatsAppConsentStatus;

import java.time.LocalDateTime;

public record WhatsAppConsentAuditResponse(
        Long id,
        String phone,
        WhatsAppConsentStatus previousStatus,
        WhatsAppConsentStatus newStatus,
        String source,
        String evidence,
        LocalDateTime consentedAt,
        Long leadId,
        String importReference,
        String performedByName,
        LocalDateTime performedAt
) {
}
