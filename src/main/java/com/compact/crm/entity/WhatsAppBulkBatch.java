package com.compact.crm.entity;

import com.compact.crm.enums.BulkEmailTarget;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// A SERVER-issued bulk WhatsApp batch (V16), the WhatsApp twin of
// EmailBulkBatch: it pins the owner, target, template id + version, the exact
// body snapshot (+ hash) and the ordered recipient record ids, so a later chunk
// can only send a subset of the original list with the original text.
// `target` reuses BulkEmailTarget (LEAD / OPPORTUNITY): same addressing and
// ownership rules as bulk email.
@Entity
@Table(
        name = "whatsapp_bulk_batch",
        uniqueConstraints = @UniqueConstraint(name = "uq_whatsapp_bulk_batch_batch_id", columnNames = "batch_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppBulkBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Column(name = "owner_employee_id", nullable = false)
    private Long ownerEmployeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, length = 20)
    private BulkEmailTarget target;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "template_version", nullable = false)
    private Integer templateVersion;

    @Column(name = "template_name", nullable = false, length = 150)
    private String templateName;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "recipient_ids", nullable = false, columnDefinition = "TEXT")
    private String recipientIds;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public List<Long> recipientIdList() {

        List<Long> ids = new ArrayList<>();

        if (recipientIds != null && !recipientIds.isBlank()) {
            for (String part : recipientIds.split(",")) {
                ids.add(Long.valueOf(part.trim()));
            }
        }

        return ids;
    }
}
