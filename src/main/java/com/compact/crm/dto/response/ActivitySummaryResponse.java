package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

// Backs the stat-card row at the top of the Employee Activity page. All
// counts are scoped/filtered exactly like the activity table below it (see
// ActivityLogService.getSummary) - an Admin viewing everyone sees
// org-wide counts, a Manager sees their team's, a filtered single-employee
// view sees just that person's.
@Getter
@AllArgsConstructor
public class ActivitySummaryResponse {

    private long totalActions;
    private long leadsCreated;
    private long leadsUpdated;
    private long followUpsCompleted;
    private long conversions;
    private LocalDateTime lastActiveAt;
    private LocalDateTime lastLoginAt;
}
