package com.compact.crm.service;

import com.compact.crm.enums.LeadStatus;
import com.compact.crm.util.BusinessClock;

import java.time.LocalDateTime;
import java.util.List;

// "Stale lead" detection - READ-ONLY. A lead is stale when ALL of these hold:
//  - its stored status is one of OPEN_STATUSES (NEW, or one of the older
//    CONTACTED / QUOTATION_SENT / NEGOTIATION values). VALID, INVALID and every
//    other legacy value are never stale, so a qualified lead is never swept up;
//  - the lead itself has not been edited since the cutoff (updatedAt);
//  - no follow-up on it was created or updated since the cutoff;
//  - it has no Opportunity (the opportunity pipeline governs its own follow-up).
// The cutoff is "now in the business timezone (Asia/Kolkata by default, see
// util.BusinessClock) minus staleAfterMonths" (default 6, property
// crm.lead.stale-after-months). Viewing a lead never counts as activity.
//
// Being stale only FLAGS a lead (Inactive Leads page, a tag in the list).
// It never changes a lead's status, never makes a lead INVALID and never
// affects conversion eligibility - only the stored status VALID does that.
public final class StaleLeadRules {

    public static final int DEFAULT_STALE_AFTER_MONTHS = 6;

    // Same open statuses the old automatic job considered.
    public static final List<LeadStatus> OPEN_STATUSES = List.of(
            LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUOTATION_SENT, LeadStatus.NEGOTIATION);

    private StaleLeadRules() {
    }

    public static LocalDateTime cutoff(int staleAfterMonths) {

        int months = staleAfterMonths > 0 ? staleAfterMonths : DEFAULT_STALE_AFTER_MONTHS;

        return BusinessClock.now().minusMonths(months);
    }
}
