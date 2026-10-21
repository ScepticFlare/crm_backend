package com.compact.crm.entity;

import com.compact.crm.enums.SalesStageType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sales_stages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Editable display name - Admins may rename any stage. Business rules
    // never depend on it; see stageType.
    @Column(nullable = false, unique = true)
    private String name;

    // Stable internal identity of a core workflow stage (null for custom /
    // historical stages). Not editable through the stage-management API, so
    // renaming a stage can never change how it behaves.
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_type", length = 30)
    private SalesStageType stageType;

    private Boolean isActive;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Computed for the UI so it never needs stage names: is an opportunity
    // in this stage still being worked? (see OpportunityWorkflow.isActive)
    @JsonProperty("workflowActive")
    public boolean isWorkflowActive() {
        return com.compact.crm.service.OpportunityWorkflow.isActive(this);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        isActive = true;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
