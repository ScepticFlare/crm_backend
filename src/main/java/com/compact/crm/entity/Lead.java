package com.compact.crm.entity;

import com.compact.crm.enums.LeadSource;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
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