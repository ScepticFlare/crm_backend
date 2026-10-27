package com.compact.crm.entity;

import com.compact.crm.enums.SuppressionReason;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// Append-only history of suppression changes (who added / removed which address,
// and - for a removal - the mandatory justification).
@Entity
@Table(name = "email_suppression_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailSuppressionAudit {

    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 20)
    private SuppressionReason reason;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "performed_by_employee_id")
    private Long performedByEmployeeId;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @PrePersist
    protected void onCreate() {
        performedAt = LocalDateTime.now();
    }
}
