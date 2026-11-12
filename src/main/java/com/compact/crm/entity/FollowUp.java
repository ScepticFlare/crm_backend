package com.compact.crm.entity;

import com.compact.crm.dto.response.FollowUpRelatedRecord;
import com.compact.crm.enums.FollowUpStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "followups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FollowUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "lead_id")
    private Lead lead;

    @ManyToOne
    @JoinColumn(name = "opportunity_id")
    private Opportunity opportunity;

    @ManyToOne
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne
    @JoinColumn(name = "activity_type_id")
    private ActivityType activityType;

    @Enumerated(EnumType.STRING)
    private FollowUpStatus status;

    private LocalDateTime scheduledDate;

    private LocalDateTime completedDate;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Not persisted. Set only on the single follow-up response (FollowUpService.
    // getFollowUpById) so the edit form asks for exactly what the backend will
    // require: true when this follow-up counts for an opportunity that still
    // needs a next follow-up (its own opportunity, or the one its lead was
    // converted into), i.e. completing it may require scheduling the next one.
    // Null when not computed.
    @Transient
    private Boolean nextFollowUpNeededOnCompletion;

    // Not persisted. With nextFollowUpNeededOnCompletion: the earliest OTHER
    // qualifying follow-up of that opportunity (on it or its lead). When set,
    // completing this one needs nothing more. Null when there is none.
    @Transient
    private LocalDateTime otherNextFollowUpDate;

    // Not persisted (field access - this is a method, not a column). What this
    // follow-up is about, from its lead / opportunity relationships only: an
    // opportunity follow-up has no lead_id, so its company and lead come from
    // the opportunity's lead. Serialized as "related" for the list, detail and
    // export. Uses relationships that are loaded eagerly anyway - no extra query.
    @JsonProperty("related")
    public FollowUpRelatedRecord getRelated() {
        return FollowUpRelatedRecord.of(lead, opportunity);
    }

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