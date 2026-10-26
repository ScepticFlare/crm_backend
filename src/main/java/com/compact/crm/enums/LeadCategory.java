package com.compact.crm.enums;

import java.util.EnumSet;
import java.util.Set;

// The four mutually exclusive lifecycle categories every lead falls into
// (each lead is in exactly one, so counts never double-count):
//
//   INVALID           - stored status INVALID (wins over everything else, the
//                       same precedence as service.LeadWorkflow.effectiveStatus)
//   CONVERTED         - not INVALID and an Opportunity exists for the lead
//   VALID_UNCONVERTED - not INVALID, no Opportunity, stored status VALID
//   NEW               - everything else (NEW, and legacy statuses that never
//                       produced an Opportunity - the conservative reading
//                       LeadWorkflow already uses)
//
// "Converted" is the existing opportunities.lead_id relationship, never a
// status label. The normal (operational) Leads page shows ACTIVE only; every
// category stays reportable/exportable.
public enum LeadCategory {
    NEW,
    VALID_UNCONVERTED,
    INVALID,
    CONVERTED;

    // What the operational Leads page lists.
    public static final Set<LeadCategory> ACTIVE = EnumSet.of(NEW, VALID_UNCONVERTED);

    // Parses the `category` request parameter of the report / export
    // endpoints. Returns null for "every category" (no restriction).
    //   blank        -> defaultIfBlank
    //   ACTIVE       -> the operational set
    //   ALL          -> null
    //   a constant   -> just that category
    public static Set<LeadCategory> parseSelection(String value, Set<LeadCategory> defaultIfBlank) {

        if (value == null || value.isBlank()) {
            return defaultIfBlank;
        }

        String v = value.trim().toUpperCase();

        if (v.equals("ALL")) {
            return null;
        }

        if (v.equals("ACTIVE")) {
            return ACTIVE;
        }

        try {
            return EnumSet.of(LeadCategory.valueOf(v));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown lead category \"" + value + "\". Use ALL, ACTIVE, NEW, VALID_UNCONVERTED, INVALID or CONVERTED.");
        }
    }
}
