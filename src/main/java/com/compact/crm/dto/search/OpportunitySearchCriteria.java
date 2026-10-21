package com.compact.crm.dto.search;

import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class OpportunitySearchCriteria {

    private String search;
    private String stageName;
    // Stable core-stage filter (used by the Postponed / Lost pages) - unlike
    // stageName it survives an Admin renaming the stage.
    private SalesStageType stageType;
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
