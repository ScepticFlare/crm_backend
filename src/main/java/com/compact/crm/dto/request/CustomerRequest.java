package com.compact.crm.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CustomerRequest {

    @NotBlank(message = "Company name is required")
    private String companyName;

    @NotBlank(message = "Contact person is required")
    private String contactPerson;

    @NotBlank(message = "Designation is required")
    private String designation;

    @NotBlank(message = "Phone is required")
    @Size(min = 10, max = 10, message = "Phone number must be exactly 10 digits")
    private String phone;

    private String alternatePhone;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    private String secondaryEmail;

    private String website;

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "State is required")
    private String state;

    private String pincode;

    @NotBlank(message = "Billing address is required")
    private String billingAddress;

    @NotBlank(message = "Shipping address is required")
    private String shippingAddress;

    private String gstNumber;

    @NotNull(message = "Assigned employee ID is required")
    private Long assignedEmployeeId;

    @NotNull(message = "Opportunity ID is required")
    private Long opportunityId;
}