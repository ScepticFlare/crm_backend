package com.compact.crm.dto.response;

import com.compact.crm.enums.FollowUpStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

// Backs the Dashboard's "Upcoming Follow Ups" widget - a JPQL constructor
// projection (see repository.FollowUpRepository.findUpcoming*) so that
// widget never has to pull a full page of FollowUp entities (each dragging
// in its own lead/opportunity/employee/activityType sub-graphs) just to
// show 5 rows. companyName is whichever of the follow-up's own lead or its
// opportunity's lead is set - mirrors the "item.lead?.companyName ||
// item.opportunity?.lead?.companyName" fallback the Dashboard used before.
@Getter
@AllArgsConstructor
public class UpcomingFollowUpResponse {

    private Long id;
    private LocalDateTime scheduledDate;
    private FollowUpStatus status;
    private Long leadId;
    private String companyName;
    private Long opportunityId;
    private String activityTypeName;
}
