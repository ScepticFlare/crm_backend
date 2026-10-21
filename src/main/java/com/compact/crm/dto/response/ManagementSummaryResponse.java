package com.compact.crm.dto.response;

import java.time.LocalDate;
import java.util.List;

// Backs GET /api/management/summary - the Director's management metrics,
// scoped to what the caller may view (Admin: everything, Manager: their
// team, Employee: their own). Every figure is computed from stored data;
// `notes` states the exact basis of the ones with a caveat.
public record ManagementSummaryResponse(
        LocalDate from,
        LocalDate to,
        Funnel funnel,
        Pipeline pipeline,
        Overdue overdue,
        List<ClosingWindow> closingSoon,
        List<OwnerResult> resultsBySalesperson,
        List<SourceResult> resultsBySource,
        LossReasons lossReasons,
        ClosureDateChanges closureDateChanges,
        UncontactedLeads uncontactedLeads,
        List<String> notes
) {

    // Leads created between from and to, as they are DISPLAYED under the
    // simplified NEW / VALID / INVALID model (see service.LeadWorkflow).
    public record Funnel(
            long leadsReceived,
            long newLeads,
            long validLeads,
            long invalidLeads,
            double validRatePercent,
            long convertedToOpportunity,
            long currentlyQuotationSent,
            long won,
            long newLeadsNow
    ) {
    }

    public record Pipeline(long openCount, double openValue, List<StageRow> byStage) {
    }

    public record StageRow(String stage, long count, double value) {
    }

    public record Overdue(
            long opportunityCount,
            double opportunityValue,
            List<OpportunityItem> opportunities,
            long followUpCount,
            List<FollowUpItem> followUps
    ) {
    }

    public record OpportunityItem(
            Long id, String title, String company, String owner, LocalDate expectedClosingDate, double value) {
    }

    public record FollowUpItem(Long id, java.time.LocalDateTime scheduledDate, String company, Long opportunityId, Long leadId) {
    }

    // Active opportunities expected to close within the next N days.
    public record ClosingWindow(int days, long count, double value) {
    }

    public record OwnerResult(String salesperson, long won, double wonValue, long lost) {
    }

    public record SourceResult(String source, long won, double wonValue, long lost) {
    }

    public record LossReasons(List<ReasonCount> reasons, long lostWithoutReason) {
    }

    public record ReasonCount(String reason, long count) {
    }

    // Opportunities whose expected closing date was changed (after being
    // set) two or more times - from the field-level history.
    public record ClosureDateChanges(long opportunitiesWithRepeatedChanges, List<ChangeCount> top) {
    }

    public record ChangeCount(Long opportunityId, String title, long changes) {
    }

    // NEW (not yet qualified, not converted) leads whose contact deadline
    // has passed with no completed follow-up.
    public record UncontactedLeads(long count, List<LeadItem> leads) {
    }

    public record LeadItem(Long id, String company, String owner, LocalDate contactDeadline) {
    }
}
