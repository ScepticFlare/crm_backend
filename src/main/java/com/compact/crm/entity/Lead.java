package com.compact.crm.entity;

import com.compact.crm.enums.LeadSource;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import jakarta.persistence.*;
import lombok.*;
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

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "industry_id")
    private Industry industry;

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

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LeadBattery> leadBatteries = new ArrayList<>();

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LeadProduct> leadProducts = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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