package com.compact.crm.dto.search;

import com.compact.crm.enums.FollowUpStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class FollowUpSearchCriteria {

    private String search;
    private Long activityTypeId;
    private FollowUpStatus status;
    private Long assignedEmployeeId;
    private Long leadId;
    private Long opportunityId;
    private LocalDate scheduledFrom;
    private LocalDate scheduledTo;
}
