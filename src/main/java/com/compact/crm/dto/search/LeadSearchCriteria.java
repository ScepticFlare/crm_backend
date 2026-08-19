package com.compact.crm.dto.search;

import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

// Bound from query params via @ModelAttribute. Every field is optional and
// combinable (AND-ed together in LeadSpecifications) - none of these ever
// reach the query as raw strings, each has a dedicated typed Specification.
@Getter
@Setter
public class LeadSearchCriteria {

    private String search;
    private LeadStatus status;
    private Long leadSourceId;
    private Long industryId;
    private Long productId;
    private Long batteryId;
    private Long assignedEmployeeId;
    private String city;
    private String state;
    private LeadValidity leadValidity;
    private LocalDate createdFrom;
    private LocalDate createdTo;
}
