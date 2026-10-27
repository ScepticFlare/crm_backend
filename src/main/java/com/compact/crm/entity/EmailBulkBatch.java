package com.compact.crm.entity;

import com.compact.crm.enums.BulkEmailTarget;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// A server-issued bulk email batch (see service.BulkEmailService). Pins who owns
// it, what is being sent and to which records, so no later request can widen the
// recipient list or change the message. subject/body are the editable copy WITH
// placeholders (each recipient is rendered at send time); the shared template is
// never touched. No foreign keys, so deleting a lead/employee never blocks or
// erases the send history.
@Entity
@Table(
        name = "email_bulk_batch",
        uniqueConstraints = @UniqueConstraint(name = "uq_email_bulk_batch_batch_id", columnNames = "batch_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailBulkBatch {

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

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    // Ordered, comma-separated record ids (at most the 100-record cap).
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
