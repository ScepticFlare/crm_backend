package com.compact.crm.dto.search;

import com.compact.crm.enums.FollowUpStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class FollowUpSearchCriteria {

    private String search;
    // Multi-valued: repeat the param to match ANY of the values.
    private java.util.List<Long> activityTypeId;
    private java.util.List<FollowUpStatus> status;
    private java.util.List<Long> assignedEmployeeId;
    private Long leadId;
    private Long opportunityId;
    private LocalDate scheduledFrom;
    private LocalDate scheduledTo;
}
