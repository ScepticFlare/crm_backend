package com.compact.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// A reusable WhatsApp message draft authored by an admin INSIDE the CRM (V15).
// Not a WhatsApp/Meta-approved template: WAHA sends plain text. The body uses
// the same {{field}} merge fields as email (EmailPlaceholderRenderer), resolved
// per recipient on the server. `version` increases whenever the body changes;
// bulk batches pin the body + version they were created with.
@Entity
@Table(name = "whatsapp_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_by_employee_id")
    private Long createdByEmployeeId;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "updated_by_employee_id")
    private Long updatedByEmployeeId;

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
