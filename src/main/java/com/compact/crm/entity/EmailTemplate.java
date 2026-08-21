package com.compact.crm.entity;

import com.compact.crm.enums.EmailTemplateType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// A reusable, org-wide email template for the Lead email feature (Product
// Brochure / Keep in Touch composer) - see service.EmailTemplateService.
// Shared across every authorized employee, not owned per-user: createdBy is
// authorship metadata only, not a visibility scope (unlike Lead.
// assignedEmployee). createdByEmployee is nullable with ON DELETE SET NULL,
// same rationale as ActivityLog.employee - a required FK would make any
// employee who ever authored a template undeletable.
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "email_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private String subject;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailTemplateType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_employee_id")
    @JsonIgnore
    private Employee createdByEmployee;

    @Column(name = "created_by_name", nullable = false)
    private String createdByName;

    @Builder.Default
    private Boolean isActive = true;

    // At most one active template per type should have this set - the
    // composer prefills from this one when the user hasn't picked a
    // template yet (see service.EmailTemplateService.getDefaultForType).
    // Users are never forced to use it; it's just the initial prefill.
    @Column(name = "is_default")
    @Builder.Default
    private Boolean isDefault = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {

        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }

        if (isActive == null) {
            isActive = true;
        }

        if (isDefault == null) {
            isDefault = false;
        }

        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @JsonProperty("createdByEmployeeId")
    public Long getCreatedByEmployeeId() {
        return createdByEmployee != null ? createdByEmployee.getId() : null;
    }
}
