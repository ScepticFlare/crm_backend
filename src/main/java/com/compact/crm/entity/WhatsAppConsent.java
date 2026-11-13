package com.compact.crm.entity;

import com.compact.crm.enums.WhatsAppConsentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// Current WhatsApp consent of ONE normalized number (V15). Keyed by the number,
// not the lead: every lead / opportunity sharing it shares this state, so an
// opt-out recorded through any of them blocks them all. History lives in
// WhatsAppConsentAudit.
@Entity
@Table(
        name = "whatsapp_consent",
        uniqueConstraints = @UniqueConstraint(name = "uq_whatsapp_consent_phone", columnNames = "phone_e164"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WhatsAppConsentStatus status;

    // Where the consent / opt-out came from, e.g. ENQUIRY_FORM, PHONE_CALL, INBOUND_STOP.
    @Column(nullable = false, length = 50)
    private String source;

    // Reference to the proof (form id, call note, document reference...).
    @Column(length = 500)
    private String evidence;

    // When the person gave consent / opted out (the documented date, not "now").
    @Column(name = "consented_at", nullable = false)
    private LocalDateTime consentedAt;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "opportunity_id")
    private Long opportunityId;

    @Column(name = "recorded_by_employee_id")
    private Long recordedByEmployeeId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
