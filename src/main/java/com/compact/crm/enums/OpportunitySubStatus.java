package com.compact.crm.enums;

// Sub-state of an Opportunity whose Sales Stage is "IN PROGRESS" - not a
// stage of its own and never a terminal outcome. Only valid while the
// stage is IN PROGRESS (see service.OpportunityWorkflow); cleared whenever
// the Opportunity moves to any other stage.
public enum OpportunitySubStatus {
    QUOTATION_SENT,
    NEGOTIATION
}
