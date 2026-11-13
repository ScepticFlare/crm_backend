package com.compact.crm.enums;

// Stable internal identity of a workflow stage, independent of its editable
// display name (SalesStage.name). Admins may rename a stage; every business
// rule keys off this type instead of the name, so renaming "WON" to
// "Closed Won" (or "DROPPED" to "Dropped by customer") cannot change
// behaviour.
//
// The workflow has exactly FIVE main stages - IN PROGRESS, WON, LOST,
// POSTPONED and DROPPED (isMainStage). Only those can be chosen for an
// Opportunity. IN PROGRESS keeps its sub-statuses (OpportunitySubStatus:
// Quotation Sent / Negotiation). Every other type is kept only so historical
// rows still load and their pages keep working - they can never be newly
// selected.
//
// A stage with a null type is a custom stage an Admin created before custom
// stages were withdrawn. Its Opportunities are moved into the five-stage
// model at startup (service.LegacyStageRetirement) and the row is kept,
// inactive, for history; no new custom stage can be created.
//
// The constant names are stored in sales_stages.stage_type, so they are never
// renamed: DROPPED keeps the constant LEGACY_DROPPED although it is a main
// stage again.
public enum SalesStageType {

    // Retired (see isRetired): existing NEW Opportunities are moved to IN
    // PROGRESS at startup (service.NewStageRetirement) and the stage row is
    // kept, inactive, for history. Never created, never selectable, and an
    // Admin cannot reactivate it.
    NEW("NEW", true, false, false, false, true),

    IN_PROGRESS("IN PROGRESS", true, true, true, true, false),
    POSTPONED("POSTPONED", false, true, true, true, false),
    WON("WON", false, true, true, true, false),
    LOST("LOST", false, true, true, true, false),

    // Retired historical stages that the agreed workflow replaced with the
    // IN PROGRESS sub-status. Their Opportunities are moved to IN PROGRESS
    // with the equivalent sub-status at startup
    // (service.LegacyStageRetirement); the rows are kept, inactive, for
    // history. Never created, never selectable, never reactivated.
    LEGACY_QUOTATION_SENT("QUOTATION SENT", true, false, false, false, true),
    LEGACY_NEGOTIATION("NEGOTIATION", true, false, false, false, true),

    // DROPPED is one of the five main stages (closed, not active).
    LEGACY_DROPPED("DROPPED", false, true, true, true, false),

    // Retired historical closed outcomes (approved mapping: UNRESPONSIVE ->
    // DROPPED, INVALID -> LOST). Their Opportunities are moved to that main
    // stage at startup (service.LegacyStageRetirement), keeping the old stage
    // as stored provenance so the Unresponsive / Invalid pages still list
    // them; the rows are kept, inactive, for history. No longer created for a
    // fresh database, never selectable, never reactivated.
    LEGACY_UNRESPONSIVE("UNRESPONSIVE", false, false, false, false, true),
    LEGACY_INVALID("INVALID", false, false, false, false, true);

    private final String defaultName;
    private final boolean active;
    private final boolean core;
    private final boolean seeded;
    private final boolean mainStage;
    private final boolean retired;

    SalesStageType(String defaultName, boolean active, boolean core, boolean seeded,
                   boolean mainStage, boolean retired) {
        this.defaultName = defaultName;
        this.active = active;
        this.core = core;
        this.seeded = seeded;
        this.mainStage = mainStage;
        this.retired = retired;
    }

    // Name used when the stage has to be created, and the (only) name an
    // existing untyped stage is matched against when typing it once.
    public String getDefaultName() {
        return defaultName;
    }

    // Still being worked (closing-date / follow-up warnings apply).
    public boolean isActive() {
        return active;
    }

    // Core stages are required by the workflow: they can be renamed but not
    // deactivated. Exactly the five main stages.
    public boolean isCore() {
        return core;
    }

    // Created at startup if no stage carries (or can unambiguously be given)
    // this type.
    public boolean isSeeded() {
        return seeded;
    }

    // One of the five main stages - the only stages an Opportunity can be
    // put into (on conversion or edit).
    public boolean isMainStage() {
        return mainStage;
    }

    // Withdrawn from the workflow: kept only for history, never reactivated.
    public boolean isRetired() {
        return retired;
    }

    // The main stage a retired historical stage's Opportunities are moved to
    // (service.LegacyStageRetirement). Null for the main stages and for NEW,
    // which has its own retirement (service.NewStageRetirement).
    public SalesStageType retiredInto() {
        return switch (this) {
            case LEGACY_QUOTATION_SENT, LEGACY_NEGOTIATION -> IN_PROGRESS;
            case LEGACY_UNRESPONSIVE -> LEGACY_DROPPED;
            case LEGACY_INVALID -> LOST;
            default -> null;
        };
    }

    // The IN PROGRESS sub-status a retired stage maps to, if any.
    public OpportunitySubStatus retiredIntoSubStatus() {
        return switch (this) {
            case LEGACY_QUOTATION_SENT -> OpportunitySubStatus.QUOTATION_SENT;
            case LEGACY_NEGOTIATION -> OpportunitySubStatus.NEGOTIATION;
            default -> null;
        };
    }
}
