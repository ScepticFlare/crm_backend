package com.compact.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

// The Opportunity's own battery line items - see OpportunityProduct.
@Entity
@Table(name = "opportunity_batteries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpportunityBattery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "opportunity_id", nullable = false)
    @JsonIgnore
    private Opportunity opportunity;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "battery_id", nullable = false)
    private Battery battery;

    @Column(nullable = false)
    private Integer quantity;

}
