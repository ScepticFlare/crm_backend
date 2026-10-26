package com.compact.crm.entity;

import com.compact.crm.enums.LeadSource;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.service.LeadWorkflow;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.LeadSourceMaster;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "leads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition = "TEXT")
    private String finalRemarks;

    private String companyName;

    private String contactPerson;

    private String designation;

    private String phone;

    private String alternatePhone;

    private String email;

    private String secondaryEmail;

    private String city;

    private String state;

    private String pincode;

    // Raw campaign identifier from a public-lead-form submission's
    // ?campaign= query param (e.g. "enterprise-brochure-2026") - see
    // service.PublicLeadService. Null for every internally-created Lead.
    // Deliberately NOT a foreign key / master-data table: a campaign code is
    // free-form print-run metadata, not a CRM concept that needs its own
    // admin-managed list the way Industry/Product/LeadSource do.
    private String campaignCode;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "industry_id")
    private Industry industry;

    // Free-text industry/business type typed by a public-lead-form visitor
    // who selected the "Other / Not Listed" Industry (see
    // config.PublicLeadIndustrySeeder / service.PublicLeadService) - the
    // Industry master is never auto-extended from public input, so this is
    // where that detail is preserved instead of being lost. Null whenever
    // industry is a normal (non-"Other") row, including every
    // internally-created Lead.
    private String otherIndustryDetail;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private LeadStatus leadStatus;

    @Enumerated(EnumType.STRING)
    private LeadValidity leadValidity;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "lead_source_id")
    private LeadSourceMaster leadSource;

    @ManyToOne
    @JoinColumn(name = "assigned_employee_id")
    private Employee assignedEmployee;

    // @BatchSize: these are LAZY and get serialized to JSON on every lead
    // list response (see pages/Leads.jsx). Without it, Hibernate issues one
    // SELECT per lead per collection when Jackson walks the list (classic
    // N+1 - up to 2 extra queries x every lead on the page). With it,
    // Hibernate loads up to 50 leads' worth of rows for a given collection
    // in a single "WHERE lead_id IN (...)" query instead.
    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 50)
    private List<LeadBattery> leadBatteries = new ArrayList<>();

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 50)
    private List<LeadProduct> leadProducts = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Not persisted. Set by LeadService.getLeadById (the lead detail response):
    // true once an Opportunity has been created from this Lead, so the UI
    // can disable "Convert to Opportunity" instead of offering a repeat the
    // backend would reject. Null when it was not computed (e.g. list rows).
    @Transient
    private Boolean converted;

    // Not persisted. Set by LeadService when lead rows are returned: true
    // for a NEW lead whose contact deadline has passed and that has no
    // completed follow-up yet. Null when not computed.
    @Transient
    private Boolean contactOverdue;

    // Not persisted. True when the lead already has a QUALIFYING follow-up
    // (pending, now or later, with a described next action - service.
    // FollowUpRules). Lets the conversion form know whether the new active
    // opportunity still needs a next action entered. Null when not computed.
    @Transient
    private Boolean hasNextAction;

    // Not persisted. True when the lead is STALE right now - open status, no
    // edit or follow-up for 6+ months, no opportunity (see service.StaleLeadRules).
    // Purely informational: it never changes the stored status and never
    // affects conversion. Null when not computed.
    @Transient
    private Boolean stale;

    // Why the lead was marked INVALID (free text; required when a lead
    // becomes INVALID). Null for every historical INVALID lead - no reason
    // is ever invented for them.
    @Column(columnDefinition = "TEXT")
    private String invalidReason;

    // "Contact by" date: defaulted a couple of days after creation so no
    // NEW lead is left without an owner-visible deadline. Null for
    // historical leads (they are never flagged as overdue).
    private java.time.LocalDate contactDeadline;

    // ---- computed for the UI (never persisted) ----

    // How the lead is displayed under the simplified NEW / VALID / INVALID
    // model - see service.LeadWorkflow.effectiveStatus. The stored
    // leadStatus is untouched.
    @JsonProperty("effectiveLeadStatus")
    public LeadStatus getEffectiveLeadStatus() {
        return LeadWorkflow.effectiveStatus(leadStatus, Boolean.TRUE.equals(converted));
    }

    // Lifecycle category (NEW / VALID_UNCONVERTED / INVALID / CONVERTED - see
    // enums.LeadCategory). Only meaningful once `converted` has been computed
    // (list and export responses); null otherwise rather than a wrong guess.
    @JsonProperty("category")
    public com.compact.crm.enums.LeadCategory getCategory() {
        return converted == null ? null : LeadWorkflow.categoryOf(leadStatus, converted);
    }

    // The original value when it is one of the older opportunity-style
    // statuses (CONTACTED, WON, DROPPED...), else null.
    @JsonProperty("legacyStatus")
    public LeadStatus getLegacyStatus() {
        return LeadWorkflow.legacyStatus(leadStatus);
    }

    // Null when the lead can be converted, otherwise why not.
    @JsonProperty("conversionBlockedReason")
    public String getConversionBlockedReason() {
        return LeadWorkflow.conversionBlockedReason(this, Boolean.TRUE.equals(converted));
    }

    // Set when leadStatus transitions into a closed/resolved outcome
    // (see LeadService.CLOSED_STATUSES), cleared back to null if the lead
    // is later reopened - reflects the current closure only, not a
    // first-ever-closed timestamp.
    private LocalDateTime closedAt;

    @PrePersist
    protected void onCreate() {

        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }

        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}