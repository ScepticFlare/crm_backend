package com.compact.crm.entity;

import com.compact.crm.enums.WhatsAppConsentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// Append-only history of every WhatsApp consent change (V15). Never updated or
// deleted by the application.
@Entity
@Table(name = "whatsapp_consent_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConsentAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    private WhatsAppConsentStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private WhatsAppConsentStatus newStatus;

    @Column(nullable = false, length = 50)
    private String source;

    @Column(length = 500)
    private String evidence;

    @Column(name = "consented_at")
    private LocalDateTime consentedAt;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "opportunity_id")
    private Long opportunityId;

    // Groups the rows written by one admin bulk import.
    @Column(name = "import_reference", length = 64)
    private String importReference;

    @Column(name = "performed_by_employee_id")
    private Long performedByEmployeeId;

    @Column(name = "performed_by_name")
    private String performedByName;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @PrePersist
    protected void onCreate() {
        if (performedAt == null) {
            performedAt = LocalDateTime.now();
        }
    }
}
