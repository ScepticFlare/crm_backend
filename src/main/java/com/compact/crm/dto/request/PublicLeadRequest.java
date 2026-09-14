package com.compact.crm.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Strict whitelist DTO for the unauthenticated public enquiry endpoint
// (POST /api/public/leads - see controller.PublicLeadController /
// service.PublicLeadService). This intentionally does NOT reuse
// dto.request.LeadRequest: LeadRequest lets the caller set leadStatus,
// assignedEmployeeId, leadSourceId, leadValidity and finalRemarks directly,
// none of which a public, unauthenticated caller may ever control. Every
// field a public submission is allowed to influence is listed here
// explicitly; nothing internal/security-sensitive is reachable through this
// class even by accident.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PublicLeadRequest {

    @NotBlank(message = "Company / Organization is required.")
    @Size(max = 150, message = "Company / Organization must be at most 150 characters.")
    private String companyName;

    @NotBlank(message = "Full Name is required.")
    @Size(max = 100, message = "Full Name must be at most 100 characters.")
    private String contactPerson;

    @Size(max = 100, message = "Designation must be at most 100 characters.")
    private String designation;

    @NotBlank(message = "Phone Number is required.")
    @Pattern(regexp = "\\d{10}", message = "Phone Number must contain exactly 10 digits.")
    private String phone;

    @Pattern(regexp = "^$|\\d{10}", message = "Alternate Phone must contain exactly 10 digits.")
    private String alternatePhone;

    @NotBlank(message = "Email Address is required.")
    @Email(message = "Please enter a valid Email Address.")
    @Size(max = 150, message = "Email Address must be at most 150 characters.")
    private String email;

    @NotBlank(message = "City is required.")
    @Size(max = 100, message = "City must be at most 100 characters.")
    private String city;

    @NotBlank(message = "State is required.")
    @Size(max = 100, message = "State must be at most 100 characters.")
    private String state;

    @Pattern(regexp = "^$|^[0-9]{6}$", message = "Pincode must contain exactly 6 digits.")
    private String pincode;

    @NotNull(message = "Please select an Industry / Business Type.")
    private Long industryId;

    // Only meaningful (and only validated as required) when industryId
    // resolves to the seeded "Other / Not Listed" Industry - see
    // PublicLeadService.createPublicLead. Not annotated @NotBlank here
    // because whether it's required depends on which industry was picked,
    // which Bean Validation can't express on its own; the service enforces
    // it conditionally instead. Silently ignored (never stored) when a
    // normal industry is selected, even if the client sends a value.
    @Size(max = 150, message = "Industry / business type must be at most 150 characters.")
    private String otherIndustryDetail;

    // Not @NotEmpty: a visitor may be interested in products only,
    // batteries only, or both - PublicLeadService.createPublicLead enforces
    // "at least one of products/batteries" as a cross-field check instead,
    // since Bean Validation can't express that on a single field. @Valid
    // still validates every item actually supplied.
    @Valid
    private List<PublicLeadProductRequest> products;

    // Same shape/rules as products above - see PublicLeadBatteryRequest.
    @Valid
    private List<PublicLeadBatteryRequest> batteries;

    @NotBlank(message = "Please tell us about your requirement.")
    @Size(max = 2000, message = "Requirement details must be at most 2000 characters.")
    private String description;

    // Which public entry point this submission came through - validated
    // server-side against a fixed whitelist (see
    // PublicLeadService.resolveLeadSource), never trusted to name a
    // LeadSourceMaster row directly.
    @NotBlank(message = "Invalid submission source.")
    @Size(max = 20)
    private String source;

    // Optional print-run identifier from a brochure QR code
    // (?campaign=enterprise-brochure-2026). Free-form but bounded and
    // format-validated - see PublicLeadService.sanitizeCampaignCode for the
    // actual character-level validation (Bean Validation's @Pattern isn't
    // used here so a malformed value degrades to "not stored" instead of
    // failing the whole submission).
    @Size(max = 100, message = "Campaign code must be at most 100 characters.")
    private String campaign;

    // Honeypot - a real visitor never sees or fills this field (hidden via
    // CSS on the form); a non-empty value here means the submission almost
    // certainly came from a bot. Never rendered as a validation error to the
    // caller - see PublicLeadService, which silently drops honeypot-tripped
    // submissions instead of rejecting them (rejecting would teach a bot
    // which field to leave blank).
    private String website;
}
