package com.compact.crm.dto.search;

import com.compact.crm.enums.LeadValidity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class OpportunitySearchCriteria {

    private String search;
    private String stageName;
    private Long salesStageId;
    private Long assignedEmployeeId;
    private Long productId;
    private Long industryId;
    private LeadValidity leadValidity;
    private LocalDate createdFrom;
    private LocalDate createdTo;
    private LocalDate expectedClosingFrom;
    private LocalDate expectedClosingTo;
    private Double minValue;
    private Double maxValue;
}
