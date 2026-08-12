package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullReportOpportunityRow {

    private Long opportunityId;

    private String title;

    private String salesStage;

    private Double productValue;

    private LocalDate expectedClosingDate;

    private String leadValidity;

    private LocalDateTime createdAt;

    private Long leadId;

    private String companyName;

    private String contactPerson;

    private String phone;

    private String email;

    private String industry;

    private String leadSource;

    private String assignedEmployeeName;

    private String assignedEmployeeEmail;

    private String products;

    private String batteries;
}
