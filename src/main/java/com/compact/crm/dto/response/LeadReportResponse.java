package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadReportResponse {

    private String month;

    private long totalLeadsReceived;

    private long invalidLeads;

    private long validLeads;

    private long won;

    private long lost;

    private long inProgress;

    private long postponed;

    private long dropped;

    private long unresponsive;

    private double wonConversionRate;

}