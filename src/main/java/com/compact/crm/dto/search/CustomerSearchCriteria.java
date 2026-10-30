package com.compact.crm.dto.search;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// The list-typed filters are multi-valued: repeat the param to match ANY of
// the values; a single value works exactly as it always did.
@Getter
@Setter
public class CustomerSearchCriteria {

    private String search;
    private List<Long> assignedEmployeeId;
    private List<Long> industryId;
    // The won Opportunity's own (current) products / batteries.
    private List<Long> productId;
    private List<Long> batteryId;
    private String city;
    private String state;
    private LocalDate createdFrom;
    private LocalDate createdTo;
}
