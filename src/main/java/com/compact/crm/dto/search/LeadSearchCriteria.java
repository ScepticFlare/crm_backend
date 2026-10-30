package com.compact.crm.dto.search;

import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// Bound from query params via @ModelAttribute. Every field is optional and
// combinable (AND-ed together in LeadSpecifications) - none of these ever
// reach the query as raw strings, each has a dedicated typed Specification.
// The list-typed filters are multi-valued: repeat the param
// (productId=1&productId=2) to match ANY of the values; a single value works
// exactly as it always did.
@Getter
@Setter
public class LeadSearchCriteria {

    private String search;
    private List<LeadStatus> status;
    // NEW / VALID / INVALID as the lead is DISPLAYED (legacy statuses mapped by
    // service.LeadWorkflow.effectiveStatus). `status` above filters the stored value.
    private List<LeadStatus> statusGroup;
    // Backs the Inactive Leads page: stored INACTIVE status OR currently stale
    // (computed read-only - see service.StaleLeadRules). Set by the endpoint.
    private Boolean inactiveView;
    private List<Long> leadSourceId;
    private List<Long> industryId;
    private List<Long> productId;
    private List<Long> batteryId;
    private List<Long> assignedEmployeeId;
    private String city;
    private String state;
    private List<LeadValidity> leadValidity;
    private LocalDate createdFrom;
    private LocalDate createdTo;
}
