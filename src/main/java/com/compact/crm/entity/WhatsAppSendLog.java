package com.compact.crm.entity;

import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.WhatsAppDeliveryStatus;
import com.compact.crm.enums.WhatsAppSendStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// One row per (batch, record): the claim taken before the provider is called
// (V16). `status` is what the send REQUEST returned (ACCEPTED is not delivery);
// `deliveryStatus` is what the provider's webhooks later reported.
@Entity
@Table(
        name = "whatsapp_send_log",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_whatsapp_send_log_batch_record",
                        columnNames = {"batch_id", "target", "target_id"}),
                @UniqueConstraint(name = "uq_whatsapp_send_log_provider_message",
                        columnNames = "provider_message_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppSendLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, length = 20)
    private BulkEmailTarget target;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "record_label", length = 255)
    private String recordLabel;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WhatsAppSendStatus status;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 20)
    private WhatsAppDeliveryStatus deliveryStatus;

    @Column(name = "delivery_updated_at")
    private LocalDateTime deliveryUpdatedAt;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(name = "sent_by_employee_id")
    private Long sentByEmployeeId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
