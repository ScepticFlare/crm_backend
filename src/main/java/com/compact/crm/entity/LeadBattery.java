package com.compact.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "lead_batteries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadBattery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "lead_id", nullable = false)
    @JsonIgnore
    private Lead lead;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "battery_id", nullable = false)
    private Battery battery;

    @Column(nullable = false)
    private Integer quantity;

}
