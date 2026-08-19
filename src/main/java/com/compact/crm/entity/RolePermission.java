package com.compact.crm.entity;

import com.compact.crm.enums.Scope;
import jakarta.persistence.*;
import lombok.*;

// Grants a Role a Permission at a given visibility Scope (OWN/TEAM/ALL).
// This is the whole permission model: "can this role do X" (Permission)
// and "over whose records" (Scope) are looked up together per request by
// security.AccessControlService, never hard-coded per role in a service.
@Entity
@Table(
        name = "role_permissions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"role_id", "permission_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Scope scope;
}
