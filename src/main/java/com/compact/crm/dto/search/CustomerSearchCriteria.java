package com.compact.crm.dto.search;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class CustomerSearchCriteria {

    private String search;
    private Long assignedEmployeeId;
    private Long industryId;
    private String city;
    private String state;
    private LocalDate createdFrom;
    private LocalDate createdTo;
}
