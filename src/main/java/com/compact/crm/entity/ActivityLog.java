package com.compact.crm.entity;

import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// A single meaningful business action performed by an employee - the whole
// audit trail is this one generic table rather than a per-module history
// table (see security.AccessControlService for the analogous "one central
// place, not scattered per-module logic" precedent). Append-only: nothing
// ever updates or deletes a row here, so unlike every other entity in this
// codebase there is deliberately no updatedAt.
//
// employee is a nullable, ON DELETE SET NULL self-reference to Employee
// (see migration V4) rather than a required FK - a required FK would make
// any employee who ever performed a single tracked action undeletable,
// breaking EmployeeService.deleteEmployee. employeeName is a snapshot
// captured at write time so a log entry stays readable ("Rahul updated a
// Lead") even after that employee's account is later deleted.
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "activity_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    @JsonIgnore
    private Employee employee;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityModule module;

    // Id of the Lead/Opportunity/Customer/FollowUp/Employee this action
    // concerns, if any (e.g. null for LOGIN/LOGOUT). Not a real FK - the
    // target can be any of several entity types and the whole point of an
    // audit trail is to remain readable even after the target is deleted.
    @Column(name = "entity_id")
    private Long entityId;

    // Human-readable identifier for the target at the time of the action
    // (e.g. a Lead's company name), so the log stays meaningful without a
    // join even after the target record changes or is deleted.
    @Column(name = "entity_name")
    private String entityName;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @JsonProperty("employeeId")
    public Long getEmployeeId() {
        return employee != null ? employee.getId() : null;
    }
}
