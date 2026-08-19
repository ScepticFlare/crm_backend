package com.compact.crm.enums;

// The CRM area an ActivityLog entry belongs to - see entity.ActivityLog.
// Deliberately flat (no per-module subclassing) so a single activity_log
// table can hold every module's entries.
public enum ActivityModule {
    AUTH,
    LEAD,
    OPPORTUNITY,
    CUSTOMER,
    FOLLOWUP,
    EMPLOYEE
}
