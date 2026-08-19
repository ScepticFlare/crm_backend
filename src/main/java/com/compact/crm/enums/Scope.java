package com.compact.crm.enums;

// Record-visibility scope for a Role's grant of a Permission.
// OWN = only records owned by the current employee.
// TEAM = OWN plus records owned by the current employee's direct reports.
// ALL = every record, no filtering.
public enum Scope {
    OWN,
    TEAM,
    ALL
}
