package com.compact.crm.entity;

import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.OpportunitySubStatus;
import com.compact.crm.service.OpportunityWorkflow;
import com.compact.crm.util.BusinessClock;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "opportunities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Opportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private Double productValue;

    private LocalDate expectedClosingDate;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sales_stage_id")
    private SalesStage salesStage;

    @Enumerated(EnumType.STRING)
    private LeadValidity leadValidity;

    // Only meaningful while the stage is of type IN_PROGRESS - see
    // OpportunityWorkflow / OpportunityService.applyWorkflowRules.
    @Enumerated(EnumType.STRING)
    private OpportunitySubStatus subStatus;

    // Free text, captured when the Opportunity is marked LOST.
    @Column(columnDefinition = "TEXT")
    private String lostReason;

    // Optional - when a POSTPONED Opportunity should be revisited.
    private LocalDate postponedReviewDate;

    // Optional: why the opportunity was postponed (free text).
    @Column(columnDefinition = "TEXT")
    private String postponedReason;

    // The review date whose reminder the employee has acknowledged. The
    // reminder is due while this differs from postponedReviewDate.
    private LocalDate postponedReminderAcknowledgedFor;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @ManyToOne
    @JoinColumn(name = "lead_id")
    private Lead lead;

    // The Opportunity's own contact details and product/battery line items.
    // Copied from the Lead on conversion (and backfilled for older
    // Opportunities by migration V14), then edited on the Opportunity only -
    // the Lead keeps its original values for traceability, and later Lead
    // edits no longer change what the Opportunity (and its reports) show.
    private String contactPerson;

    private String contactPhone;

    private String contactEmail;

    // @BatchSize: serialized on every list row, same N+1 reasoning as
    // Lead.leadProducts / Lead.leadBatteries.
    @OneToMany(mappedBy = "opportunity", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 50)
    private List<OpportunityProduct> opportunityProducts = new ArrayList<>();

    @OneToMany(mappedBy = "opportunity", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 50)
    private List<OpportunityBattery> opportunityBatteries = new ArrayList<>();

    // Not persisted. Set by OpportunityService for responses: true when the
    // Opportunity is ACTIVE and has no qualifying follow-up (pending, scheduled
    // now or later, with a described next action - see service.FollowUpRules -
    // on itself or its originating Lead). That is the state saving it is
    // rejected in. Null when it was not computed.
    @Transient
    private Boolean noNextAction;

    // Not persisted. True when a qualifying follow-up exists, whatever the
    // stage (used e.g. when a POSTPONED opportunity is being resumed).
    @Transient
    private Boolean hasNextAction;

    // Not persisted. Earliest PENDING follow-up scheduled now or later on
    // the Opportunity or its originating Lead (same follow-ups hasNextAction
    // looks at). Null when there is none or it was not computed.
    @Transient
    private LocalDateTime nextFollowUpDate;

    // Computed flags for the UI (never persisted, never change the stage):

    // Active Opportunity whose expected closing date is before today.
    @JsonProperty("closingDateExpired")
    public boolean isClosingDateExpired() {
        return expectedClosingDate != null
                && expectedClosingDate.isBefore(BusinessClock.today())
                && OpportunityWorkflow.isActive(this);
    }

    // POSTPONED with a review date that has arrived and not yet been
    // acknowledged for that date.
    @JsonProperty("postponedReminderDue")
    public boolean isPostponedReminderDue() {
        return postponedReviewDate != null
                && salesStage != null
                && OpportunityWorkflow.is(salesStage, com.compact.crm.enums.SalesStageType.POSTPONED)
                && !postponedReviewDate.isAfter(BusinessClock.today())
                && !postponedReviewDate.equals(postponedReminderAcknowledgedFor);
    }

    // Sub-status as users should see it - includes historical Opportunities
    // still in the old QUOTATION SENT / NEGOTIATION stages.
    @JsonProperty("effectiveSubStatus")
    public OpportunitySubStatus getEffectiveSubStatus() {
        return OpportunityWorkflow.effectiveSubStatus(this);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}