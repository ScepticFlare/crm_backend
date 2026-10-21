package com.compact.crm.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// Optional body for POST /api/opportunities/{id}/return-to-in-progress - a
// POSTPONED Opportunity usually has a stale expected closing date, so the
// caller may supply a fresh one while resuming it.
@Getter
@Setter
@NoArgsConstructor
public class ReturnToInProgressRequest {

    private LocalDate expectedClosingDate;

    // Resuming makes the opportunity ACTIVE again, so it needs a qualifying
    // pending follow-up. Supply the next action here when none exists (all
    // three together); the follow-up is created in the same transaction.
    private String nextActionDescription;

    private java.time.LocalDateTime nextFollowUpDate;

    private Long nextActionActivityTypeId;
}
