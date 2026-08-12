package com.compact.crm.entity;

import com.compact.crm.enums.LeadValidity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @ManyToOne
    @JoinColumn(name = "lead_id")
    private Lead lead;

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