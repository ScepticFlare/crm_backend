package com.compact.crm.service;

import com.compact.crm.entity.Lead;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;

import java.util.EnumSet;
import java.util.Set;

// Rules for the simplified Lead workflow (NEW / VALID / INVALID), in one
// place so LeadService, OpportunityService (conversion), the specifications
// and the Lead entity's computed fields all agree.
public final class LeadWorkflow {

    // The only statuses a user can choose. Everything else is legacy.
    public static final Set<LeadStatus> CORE_STATUSES =
            EnumSet.of(LeadStatus.NEW, LeadStatus.VALID, LeadStatus.INVALID);

    // A new lead should be contacted within this many days. Used to default
    // Lead.contactDeadline; the user may change it.
    public static final int DEFAULT_CONTACT_DEADLINE_DAYS = 2;

    private LeadWorkflow() {
    }

    public static boolean isCore(LeadStatus status) {
        return status != null && CORE_STATUSES.contains(status);
    }

    // The stored legacy value, or null when the lead already uses NEW /
    // VALID / INVALID.
    public static LeadStatus legacyStatus(LeadStatus stored) {
        return stored == null || isCore(stored) ? null : stored;
    }

    // How a lead is shown under the simplified model. Nothing is rewritten:
    //  - INVALID stays INVALID;
    //  - a lead that already has an Opportunity was necessarily qualified,
    //    so it shows as VALID whatever legacy value it carries;
    //  - VALID stays VALID;
    //  - everything else (NEW, and legacy values such as CONTACTED /
    //    DROPPED / INACTIVE that never produced an Opportunity) shows as NEW
    //    - the conservative reading - with the original value still
    //    available as legacyStatus.
    public static LeadStatus effectiveStatus(LeadStatus stored, boolean converted) {

        if (stored == LeadStatus.INVALID) {
            return LeadStatus.INVALID;
        }

        if (converted || stored == LeadStatus.VALID) {
            return LeadStatus.VALID;
        }

        return LeadStatus.NEW;
    }

    // The lifecycle category (see enums.LeadCategory) - the in-memory twin of
    // LeadSpecifications.inCategories, kept side by side so a row's label and
    // the query that selected it always agree.
    public static com.compact.crm.enums.LeadCategory categoryOf(LeadStatus stored, boolean converted) {

        if (stored == LeadStatus.INVALID) {
            return com.compact.crm.enums.LeadCategory.INVALID;
        }

        if (converted) {
            return com.compact.crm.enums.LeadCategory.CONVERTED;
        }

        return stored == LeadStatus.VALID
                ? com.compact.crm.enums.LeadCategory.VALID_UNCONVERTED
                : com.compact.crm.enums.LeadCategory.NEW;
    }

    // Null when the lead may be converted, otherwise the reason it may not.
    // Single source of truth for the backend check and the UI tooltip.
    public static String conversionBlockedReason(Lead lead, boolean converted) {

        if (converted) {
            return "This lead has already been converted into an Opportunity.";
        }

        LeadStatus stored = lead.getLeadStatus();

        if (stored == LeadStatus.VALID && lead.getLeadValidity() != LeadValidity.INVALID) {
            return null;
        }

        String base = "Only VALID leads can be converted into an opportunity. \""
                + lead.getCompanyName() + "\" is not marked VALID.";

        if (stored == LeadStatus.INVALID || lead.getLeadValidity() == LeadValidity.INVALID) {
            return base + " It is marked INVALID.";
        }

        if (stored == LeadStatus.NEW || stored == null) {
            return base + " Mark the lead VALID first.";
        }

        return base + " It carries the older status " + stored.name().replace('_', ' ')
                + " - review it and mark it VALID first.";
    }
}
