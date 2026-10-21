package com.compact.crm.enums;

// Stable internal identity of a workflow stage, independent of its editable
// display name (SalesStage.name). Admins may rename any stage; every business
// rule keys off this type instead of the name, so renaming "WON" to
// "Closed Won" (or "DROPPED" to "Dropped by customer") cannot change
// behaviour.
//
// A stage with a null type is an ordinary custom stage an Admin created.
public enum SalesStageType {

    NEW("NEW", true, true, true),
    IN_PROGRESS("IN PROGRESS", true, true, true),
    POSTPONED("POSTPONED", false, true, true),
    WON("WON", false, true, true),
    LOST("LOST", false, true, true),

    // Historical stages that the agreed workflow replaced with the
    // IN PROGRESS sub-status. Existing Opportunities keep them (and show as
    // the equivalent sub-status); they cannot be newly selected and are
    // never created for a fresh database.
    LEGACY_QUOTATION_SENT("QUOTATION SENT", true, false, false),
    LEGACY_NEGOTIATION("NEGOTIATION", true, false, false),

    // Historical closed outcomes. Each has its own Opportunities page and
    // report column; none is "active" (no closing-date / follow-up
    // warnings). They are still created for a fresh database and can be
    // deactivated by an Admin like any non-core stage.
    LEGACY_DROPPED("DROPPED", false, false, true),
    LEGACY_UNRESPONSIVE("UNRESPONSIVE", false, false, true),
    LEGACY_INVALID("INVALID", false, false, true);

    private final String defaultName;
    private final boolean active;
    private final boolean core;
    private final boolean seeded;

    SalesStageType(String defaultName, boolean active, boolean core, boolean seeded) {
        this.defaultName = defaultName;
        this.active = active;
        this.core = core;
        this.seeded = seeded;
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
    // deactivated.
    public boolean isCore() {
        return core;
    }

    // Created at startup if no stage carries (or can unambiguously be given)
    // this type.
    public boolean isSeeded() {
        return seeded;
    }
}
