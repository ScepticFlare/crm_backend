package com.compact.crm.entity;

import jakarta.persistence.*;
import lombok.*;

// A single capability (e.g. "LEAD_VIEW"). Seeded via migration; not
// admin-manageable through a CRUD UI in this iteration.
@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;
}
