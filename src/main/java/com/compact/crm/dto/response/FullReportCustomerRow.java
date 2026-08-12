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
public class FullReportCustomerRow {

    private Long customerId;

    private String customerCode;

    private String companyName;

    private String contactPerson;

    private String designation;

    private String phone;

    private String alternatePhone;

    private String email;

    private String secondaryEmail;

    private String website;

    private String city;

    private String state;

    private String pincode;

    private String billingAddress;

    private String shippingAddress;

    private String gstNumber;

    private String assignedEmployeeName;

    private String assignedEmployeeEmail;

    private LocalDateTime createdAt;

    private Long opportunityId;

    private String opportunityTitle;

    private String salesStage;

    private Double productValue;

    private Long leadId;

    private String industry;

    private String leadSource;

    private String products;

    private String batteries;
}
