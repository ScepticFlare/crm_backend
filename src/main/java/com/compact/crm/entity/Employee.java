package com.compact.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Employee is the target of a lazy, self-referential @ManyToOne (manager)
// - see below. When an Employee is loaded as part of resolving *another*
// employee's manager relationship (see AccessControlService.teamMemberIds
// + EmployeeService.getAllEmployees) before its own row is otherwise
// fetched, Hibernate can hand back a still-proxied instance whose runtime
// class is a generated Employee$HibernateProxy$... subclass rather than
// plain Employee. That subclass exposes extra bean properties
// (hibernateLazyInitializer / handler) injected by the ByteBuddy proxy
// generator that don't exist on Employee and so never picked up the
// @JsonIgnore below - Jackson's default runtime-type introspection then
// tries to serialize them anyway and fails (no serializer for
// ByteBuddyInterceptor). @JsonIgnoreProperties on the class is honored for
// generated subclasses too, unlike a field-level @JsonIgnore, so this is
// what actually closes the gap - see EmployeeSerializationTest.
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;

    // Role is now admin-managed reference data (see entity.Role /
    // security.AccessControlService) rather than a hard-coded enum. Kept
    // JSON-ignored on the field so it never serializes as a nested object -
    // getRoleName() below preserves the plain "role": "ADMIN" string shape
    // the frontend already expects.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    @JsonIgnore
    private Role role;

    // Direct manager, if any. Self-referential and one level deep by design
    // - a Manager's "team" is themselves plus whoever points their
    // manager_id at them (see security.AccessControlService.teamMemberIds).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    @JsonIgnore
    private Employee manager;

    @Column(unique = true,nullable = false)
    private String email;
    @Column(unique = true, nullable = false)
    private String phone;
    @Column(nullable = false)
    @JsonIgnore
    private String password;


    private Boolean isActive;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        isActive = true;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Flat "role": "ADMIN" string in JSON, matching the old enum's wire
    // shape, even though role is now a Role entity underneath.
    @JsonProperty("role")
    public String getRoleName() {
        return role != null ? role.getName() : null;
    }

    @JsonProperty("managerId")
    public Long getManagerId() {
        return manager != null ? manager.getId() : null;
    }

    @JsonProperty("managerName")
    public String getManagerName() {
        return manager != null ? manager.getName() : null;
    }

    // Detects (without throwing) an employee whose manager reference no
    // longer satisfies the hierarchy invariant enforced going forward by
    // EmployeeService (managerId must point to a MANAGER) - e.g. data left
    // over from before this validation existed, such as a Manager who was
    // demoted to Employee while still having direct reports pointing at
    // them. Surfaced so the frontend/API can detect and report the issue
    // instead of the list silently looking fine or a consumer crashing on
    // an assumption that no longer holds. Never auto-corrected here.
    @JsonProperty("hierarchyValid")
    public boolean isHierarchyValid() {
        return manager == null || "MANAGER".equalsIgnoreCase(manager.getRoleName());
    }
}