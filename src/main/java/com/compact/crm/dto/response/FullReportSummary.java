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
public class FullReportSummary {

    private long totalLeads;

    private long validLeads;

    private long invalidLeads;

    private long totalOpportunities;

    private long won;

    private long lost;

    private long inProgress;

    private long postponed;

    private long dropped;

    private long unresponsive;

    private long totalCustomers;

    private List<FullReportItemTotal> productTotals;

    private List<FullReportItemTotal> batteryTotals;
}
