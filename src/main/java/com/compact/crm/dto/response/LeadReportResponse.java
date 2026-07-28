package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadReportResponse {

    private long totalLeads;

    private long wonLeads;

    private long lostLeads;

    private Map<String, Integer> leadsByEmployee;

    private Map<String, Integer> leadsBySource;

}