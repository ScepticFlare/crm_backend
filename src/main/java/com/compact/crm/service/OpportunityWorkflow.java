package com.compact.crm.service;

import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.OpportunitySubStatus;
import com.compact.crm.enums.SalesStageType;

// Stage rules for the agreed Opportunity workflow, kept in one place so
// OpportunityService, DashboardService, the reports, the specifications and
// the entities' computed flags all agree.
//
// Everything here keys off SalesStage.stageType (a stable internal identity),
// never the editable display name. A stage without a type is an ordinary
// custom stage an Admin created: it is treated as active and is never given
// WON / LOST / DROPPED... behaviour because of what it happens to be called.
public final class OpportunityWorkflow {

    private OpportunityWorkflow() {
    }

    // Used only to recognise an existing, untyped stage as one of the core
    // stages (SalesStageService.ensureCoreStages). Case, surrounding blanks,
    // runs of spaces and underscores are ignored - production already has a
    // stage called "IN_PROGRESS", which must be recognised as IN PROGRESS
    // rather than left as a custom stage next to a newly created duplicate.
    // A genuine misspelling (e.g. "IN_PROGESS") still does not match.
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase().replaceAll("[\\s_]+", " ");
    }

    public static SalesStageType typeOf(SalesStage stage) {
        return stage == null ? null : stage.getStageType();
    }

    public static boolean is(SalesStage stage, SalesStageType type) {
        return typeOf(stage) == type;
    }

    // Still being worked on: closing-date and follow-up warnings apply.
    // WON / LOST / POSTPONED and the historical closed stages are not; a
    // custom (untyped) stage is.
    public static boolean isActive(SalesStage stage) {

        if (stage == null) {
            return false;
        }

        return stage.getStageType() == null || stage.getStageType().isActive();
    }

    public static boolean isActive(Opportunity o) {
        return o.getSalesStage() != null && isActive(o.getSalesStage());
    }

    // Grouping key used by the reports: the type name for a typed stage (so a
    // renamed WON still counts as WON), CUSTOM for any custom stage.
    public static String key(SalesStage stage) {

        if (stage == null) {
            return "";
        }

        return stage.getStageType() != null ? stage.getStageType().name() : "CUSTOM";
    }

    // The sub-status shown to users: the stored one, or - for historical
    // Opportunities still sitting in the old QUOTATION SENT / NEGOTIATION
    // stages - the equivalent sub-status, derived (never written back).
    public static OpportunitySubStatus effectiveSubStatus(Opportunity o) {

        SalesStageType type = typeOf(o.getSalesStage());

        if (type == SalesStageType.LEGACY_QUOTATION_SENT) return OpportunitySubStatus.QUOTATION_SENT;
        if (type == SalesStageType.LEGACY_NEGOTIATION) return OpportunitySubStatus.NEGOTIATION;

        return o.getSubStatus();
    }
}
