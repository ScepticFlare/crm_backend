package com.compact.crm.entity;

import com.compact.crm.enums.SuppressionReason;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// An address that must not be emailed. Keyed by the NORMALIZED address (trimmed,
// lower-case) - it applies to every lead/opportunity that shares the address.
@Entity
@Table(
        name = "email_suppression",
        uniqueConstraints = @UniqueConstraint(name = "uq_email_suppression_email", columnNames = "email_normalized"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private SuppressionReason reason;

    // Where the entry came from, e.g. ADMIN_CONSOLE (room for a provider webhook later).
    @Column(name = "source", nullable = false, length = 50)
    private String source;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by_employee_id")
    private Long createdByEmployeeId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
