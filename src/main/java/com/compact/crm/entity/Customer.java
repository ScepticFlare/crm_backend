package com.compact.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String customerCode;
    @Column(nullable = false)
    private String companyName;
    @Column(nullable = false)
    private String contactPerson;

    private String designation;
    @Column(nullable = false)
    private String phone;

    private String alternatePhone;
    @Column(nullable = false)
    private String email;

    private String secondaryEmail;

    private String website;

    private String city;

    private String state;

    private String pincode;

    private String billingAddress;

    private String shippingAddress;

    private String gstNumber;

    @ManyToOne
    @JoinColumn(name = "assigned_employee_id")
    private Employee assignedEmployee;

    @OneToOne
    @JoinColumn(name = "opportunity_id")
    private Opportunity opportunity;

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