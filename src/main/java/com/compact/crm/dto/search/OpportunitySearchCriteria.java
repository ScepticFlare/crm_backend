package com.compact.crm.dto.search;

import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// Bound from query params via @ModelAttribute. The id / enum filters are
// multi-valued: repeat the param (salesStageId=1&salesStageId=2) to match
// ANY of the values; a single value works exactly as it always did. Different
// filters are AND-ed (see OpportunityService.buildSpecification).
@Getter
@Setter
public class OpportunitySearchCriteria {

    private String search;
    private String stageName;
    // Stable core-stage filter (used by the Postponed / Lost pages) - unlike
    // stageName it survives an Admin renaming the stage.
    private SalesStageType stageType;
    // Only stages that are still being worked (the In Progress page). Set by
    // the /in-progress endpoint; also accepted by /export so the In Progress
    // page exports exactly the rows it lists.
    private Boolean activeOnly;
    private List<Long> salesStageId;
    private List<Long> assignedEmployeeId;
    private List<Long> productId;
    private List<Long> batteryId;
    private List<Long> industryId;
    private List<LeadValidity> leadValidity;
    private LocalDate createdFrom;
    private LocalDate createdTo;
    private LocalDate expectedClosingFrom;
    private LocalDate expectedClosingTo;
    private Double minValue;
    private Double maxValue;
}
