package com.compact.crm.dto.search;

import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

// Bound from query params via @ModelAttribute, same convention as
// LeadSearchCriteria/CustomerSearchCriteria/FollowUpSearchCriteria. Every
// field is optional and combinable (AND-ed together in
// ActivityLogSpecifications). employeeId here is a caller-requested filter
// ("show me just this person's activity") - service.ActivityLogService
// intersects it against the RBAC-resolved visible-employee set, it is
// never trusted on its own.
@Getter
@Setter
public class ActivityLogSearchCriteria {

    private Long employeeId;
    private ActivityModule module;
    private ActivityAction action;
    private LocalDate createdFrom;
    private LocalDate createdTo;
}
