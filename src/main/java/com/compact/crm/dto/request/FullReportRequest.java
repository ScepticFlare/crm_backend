package com.compact.crm.dto.request;

import com.compact.crm.enums.LeadValidity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FullReportRequest {

    private LocalDate from;

    private LocalDate to;

    // LEAD, OPPORTUNITY, CUSTOMER - null/empty means all three
    private Set<String> recordTypes;

    // LEAD_INFO, OPPORTUNITY_INFO, CUSTOMER_INFO, PRODUCT_INFO, BATTERY_INFO, EMPLOYEE_INFO
    // null/empty means all sections
    private Set<String> includeSections;

    private Long employeeId;

    private Long leadSourceId;

    private Long industryId;

    private Long salesStageId;

    private Long productId;

    private Long batteryId;

    private LeadValidity leadValidity;
}
