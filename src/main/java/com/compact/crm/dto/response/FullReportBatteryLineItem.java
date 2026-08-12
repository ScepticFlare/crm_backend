package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullReportBatteryLineItem {

    private Long leadId;

    private String companyName;

    private String contactPerson;

    private String batteryName;

    private Integer quantity;
}
