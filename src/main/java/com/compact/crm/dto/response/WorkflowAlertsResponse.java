package com.compact.crm.dto.response;

import java.time.LocalDate;
import java.util.List;

// Backs GET /api/opportunities/workflow-alerts - non-blocking reminders for
// the Dashboard, scoped to the Opportunities the caller may view. Nothing
// here changes any Opportunity; the lists are capped samples, the counts
// are exact.
public record WorkflowAlertsResponse(
        long expiredClosingDateCount,
        List<Item> expiredClosingDate,
        long noNextActionCount,
        List<Item> noNextAction,
        long postponedReminderCount,
        List<Item> postponedReminders
) {

    public record Item(
            Long id,
            String title,
            String companyName,
            String stage,
            LocalDate expectedClosingDate,
            LocalDate postponedReviewDate
    ) {
    }
}
