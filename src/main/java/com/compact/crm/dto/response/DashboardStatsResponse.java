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

    // Opportunity count per main stage (IN_PROGRESS, POSTPONED, WON, LOST,
    // DROPPED), by stable stage type - covers every opportunity in scope, not
    // just the first 50. Together with otherStages every opportunity in scope
    // is counted exactly once.
    private Map<String, Long> pipeline;

    private List<RecentLeadResponse> recentLeads;
    private List<UpcomingFollowUpResponse> upcomingFollowUps;

    // Of the opportunities counted under IN_PROGRESS in `pipeline`, how many
    // are at each sub-status (QUOTATION_SENT / NEGOTIATION) or have none
    // (NO_SUB_STATUS). These add up to pipeline.IN_PROGRESS - they are a
    // breakdown of it, not extra opportunities.
    private Map<String, Long> inProgressBreakdown;

    // Opportunities still in a stage that is not one of the five main stages
    // (historical UNRESPONSIVE / INVALID, or a custom stage): counted here so
    // none is silently left out, without presenting them as main stages.
    private Map<String, Long> otherStages;

    // Internal carrier for DashboardService.tally - the maps that fill
    // `pipeline`, `inProgressBreakdown` and `otherStages`.
    public record PipelineCounts(
            Map<String, Long> pipeline, Map<String, Long> inProgressBreakdown, Map<String, Long> otherStages) {
    }
}
