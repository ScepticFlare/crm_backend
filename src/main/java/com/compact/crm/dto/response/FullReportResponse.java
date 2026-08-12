package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullReportResponse {

    private FullReportSummary summary;

    private List<FullReportLeadRow> leads;

    private List<FullReportOpportunityRow> opportunities;

    private List<FullReportCustomerRow> customers;

    private List<FullReportProductLineItem> productLineItems;

    private List<FullReportBatteryLineItem> batteryLineItems;
}
