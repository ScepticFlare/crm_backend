package com.compact.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// One row per distinct provider webhook event (V16). `eventKey` is UNIQUE, so a
// redelivered webhook is recognised and ignored. `applied` is false for a
// delivery status that arrived before its send-log row existed; it is applied
// once the row is written. Never stores inbound message text.
@Entity
@Table(
        name = "whatsapp_webhook_event",
        uniqueConstraints = @UniqueConstraint(name = "uq_whatsapp_webhook_event_key", columnNames = "event_key"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, length = 300)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "ack")
    private Integer ack;

    @Column(name = "session_name", length = 100)
    private String sessionName;

    @Column(name = "applied", nullable = false)
    @Builder.Default
    private Boolean applied = false;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
    }
}
