package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullReportLeadRow {

    private Long leadId;

    private String companyName;

    private String contactPerson;

    private String designation;

    private String phone;

    private String alternatePhone;

    private String email;

    private String secondaryEmail;

    private String city;

    private String state;

    private String pincode;

    private String industry;

    private String leadSource;

    private String leadStatus;

    private String leadValidity;

    private String assignedEmployeeName;

    private String assignedEmployeeEmail;

    private String description;

    private String finalRemarks;

    private LocalDateTime createdAt;

    private String products;

    private String batteries;
}
