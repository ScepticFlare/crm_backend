package com.compact.crm.dto.request;

import com.compact.crm.enums.FollowUpStatus;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FollowUpRequest {

    private Long leadId;

    private Long opportunityId;

    @NotNull(message = "Assigned employee is required")
    private Long employeeId;

    @NotNull(message = "Activity Type is required")
    private Long activityTypeId;

    private FollowUpStatus status;

    @NotNull(message = "Follow-up date is required")
    @FutureOrPresent(message = "Follow-up date cannot be in the past")
    private LocalDateTime scheduledDate;

    private LocalDateTime completedDate;

    private String remarks;

    // The NEXT follow-up, supplied when this one is being completed (all three
    // go together). Required when completing it would leave its opportunity
    // without a next follow-up and the opportunity still needs one (it is
    // active or POSTPONED - see FollowUpService.updateFollowUp). Ignored otherwise.
    private String nextActionDescription;

    private LocalDateTime nextFollowUpDate;

    private Long nextActionActivityTypeId;
}