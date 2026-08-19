package com.compact.crm.enums;

// The exact, closed set of meaningful business actions tracked by
// ActivityLogService - not every possible request, only the ones that
// matter for an audit trail. See security/AccessControlService.java's
// permission-constant style for the parallel "small closed vocabulary,
// not free text" convention this follows.
public enum ActivityAction {

    // AUTH
    LOGIN,
    LOGOUT,
    FAILED_LOGIN,

    // Generic CRUD/business verbs shared across LEAD / OPPORTUNITY /
    // CUSTOMER / FOLLOWUP (the module field disambiguates "what").
    CREATE,
    VIEW,
    UPDATE,
    DELETE,
    IMPORT,
    EXPORT,
    CONVERT,
    COMPLETE,

    // EMPLOYEE / RBAC - specific verbs rather than generic CREATE/UPDATE,
    // since these carry distinct business meaning worth filtering on
    // separately (e.g. "show me every role change", not just "every
    // employee update").
    EMPLOYEE_CREATED,
    EMPLOYEE_UPDATED,
    ROLE_CHANGED,
    MANAGER_CHANGED,
    EMPLOYEE_ACTIVATED,
    EMPLOYEE_DEACTIVATED
}
