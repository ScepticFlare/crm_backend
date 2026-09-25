package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

// Backs GET /api/dashboard/stats - replaces the Dashboard's previous
// approach of fetching a full page (50 rows) each of leads/customers/
// opportunities/followups just to read totalElements and eyeball the first
// page for "recent"/"upcoming" widgets (see service.DashboardService).
@Getter
@AllArgsConstructor
public class DashboardStatsResponse {

    private long leads;
    private long customers;
    private long opportunities;
    private long followUps;

    // Opportunity count grouped by sales stage name (e.g. "NEW" -> 4) -
    // covers every open opportunity in scope, not just the first 50.
    private Map<String, Long> pipeline;

    private List<RecentLeadResponse> recentLeads;
    private List<UpcomingFollowUpResponse> upcomingFollowUps;
}
