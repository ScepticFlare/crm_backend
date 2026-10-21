package com.compact.crm.enums;

// The user-facing Lead Status is exactly NEW / VALID / INVALID:
//   NEW     - captured, not yet qualified
//   VALID   - a genuine prospect; the only status from which a lead may be
//             converted into an Opportunity
//   INVALID - unusable enquiry (requires a reason)
//
// Every other constant is a LEGACY value kept so historical leads still load
// and keep their original value (opportunity-style outcomes such as WON /
// LOST / QUOTATION_SENT used to be stored here). Legacy values are never
// removed or rewritten, are never offered for new selection, and are shown
// through a compatibility mapping (see service.LeadWorkflow). Removing one
// would also break startup: config.LeadStatusConstraintSync rebuilds the
// database check constraint from this enum.
public enum LeadStatus {
    NEW,
    VALID,

    // ---- legacy values (kept for historical records) ----
    CONTACTED,
    QUOTATION_SENT,
    NEGOTIATION,
    WON,
    LOST,
    DROPPED,
    UNRESPONSIVE,
    INVALID,
    // HISTORICAL ONLY. An earlier scheduled job used to write this value for a
    // Lead with no activity for 6+ months. Nothing writes it any more: a stale
    // lead is now only FLAGGED, read-only (see service.StaleLeadRules), and
    // keeps its real status. Existing INACTIVE rows (and their history) are
    // preserved and still listed on the Inactive Leads page.
    INACTIVE
}
