package com.compact.crm.entity;

import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.EmailSendStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// One row per (batch, record) of a bulk email - the duplicate-submission and
// ambiguous-retry guard (see service.BulkEmailService). Stores identifiers,
// the recipient address and a sanitized provider outcome only: never the
// message subject/body, credentials, or request payloads. Deliberately has no
// foreign keys, so deleting a lead/opportunity/employee is never blocked by
// (and never loses) the send history.
@Entity
@Table(
        name = "email_send_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_email_send_log_batch_record",
                columnNames = {"batch_id", "target", "target_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailSendLog {

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

    // Lead company name, for the results view (the record can change later).
    @Column(name = "record_label", length = 255)
    private String recordLabel;

    // Lower-cased, trimmed.
    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmailSendStatus status;

    // Sanitized: provider HTTP status and its short error message only.
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "template_id")
    private Long templateId;

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
        updatedAt = now;
    }
}
