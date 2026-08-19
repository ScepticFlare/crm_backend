package com.compact.crm.enums;

public enum LeadStatus {
    NEW,
    CONTACTED,
    QUOTATION_SENT,
    NEGOTIATION,
    WON,
    LOST,
    DROPPED,
    UNRESPONSIVE,
    INVALID,
    // Automatic outcome of scheduler.StaleLeadScheduler only - a Lead with
    // no meaningful activity for 6+ months. Deliberately separate from
    // INVALID: INVALID represents a genuinely bad/unusable Lead (a business
    // judgment), INACTIVE represents a Lead that was fine but has simply
    // gone cold. Nothing else in the app ever sets this value.
    INACTIVE
}