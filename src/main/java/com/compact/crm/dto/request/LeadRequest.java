package com.compact.crm.dto.request;

import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LeadRequest {

    @NotBlank(message = "Company Name is required.")
    private String companyName;

    @NotBlank(message = "Contact Person is required.")
    private String contactPerson;

    @NotBlank(message = "Designation is required.")
    private String designation;

    @NotBlank(message = "Phone Number is required.")
    @Size(min = 10, max = 10, message = "Phone Number must contain exactly 10 digits.")
    @Pattern(regexp = "\\d{10}", message = "Phone Number must contain only digits.")
    private String phone;

    private String alternatePhone;

    @NotBlank(message = "Email Address is required.")
    @Email(message = "Please enter a valid Email Address.")
    private String email;

    private String secondaryEmail;

    private String website;

    @NotBlank(message = "City is required.")
    private String city;

    @NotBlank(message = "State is required.")
    private String state;

    // Make this @NotBlank if your company wants Pincode mandatory.
    private String pincode;

    @NotNull(message = "Please select a Product.")
    private Long productId;

    @NotNull(message = "Please select an Industry.")
    private Long industryId;

    @NotNull(message = "Please select a Lead Source.")
    private Long leadSourceId;

    private String description;

    @NotNull(message = "Please select a Lead Status.")
    private LeadStatus leadStatus;

    private LeadValidity leadValidity;

    @NotNull(message = "Please select an Assigned Employee.")
    private Long assignedEmployeeId;
    private String finalRemarks;
}