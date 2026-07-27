package com.compact.crm.entity;

import com.compact.crm.enums.LeadSource;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import jakarta.persistence.*;
import lombok.*;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.LeadSourceMaster;

import java.time.LocalDateTime;

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

    private String website;

    private String city;

    private String state;

    private String pincode;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id")
    private Product product;

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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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