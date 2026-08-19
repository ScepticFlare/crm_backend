package com.compact.crm.entity;

import jakarta.persistence.*;
import lombok.*;

// Admin-managed role tier (ADMIN / MANAGER / EMPLOYEE, seeded via migration).
// What a Role can do is defined by its RolePermission rows, not by code
// branching on this name - see security.AccessControlService.
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    // Higher = more senior. Not used for permission bypass (RolePermission
    // owns that) - it's for hierarchy-ordering/display and future rules
    // like "can't assign a role above your own".
    @Column(name = "role_rank", nullable = false)
    private Integer rank;
}
