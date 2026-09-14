package com.compact.crm.service;

import com.compact.crm.config.PublicLeadIndustrySeeder;
import com.compact.crm.config.PublicLeadSourceSeeder;
import com.compact.crm.dto.request.PublicLeadBatteryRequest;
import com.compact.crm.dto.request.PublicLeadProductRequest;
import com.compact.crm.dto.request.PublicLeadRequest;
import com.compact.crm.dto.response.PublicLeadResponse;
import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Product;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.BatteryRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.security.PublicLeadRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

// Backs POST /api/public/leads - the ONLY unauthenticated write endpoint in
// the app (see config.SecurityConfig, controller.PublicLeadController).
// Every field on the resulting Lead that is internal/security-sensitive
// (leadStatus, assignedEmployee, leadSource, leadValidity, finalRemarks) is
// set here, server-side, from fixed values or server-side lookups - never
// copied from PublicLeadRequest. This deliberately does not reuse
// LeadService.createLead, which trusts LeadRequest for exactly those
// fields; sharing that method would require re-deriving the same
// "ignore what the client sent" logic on top of it for no real benefit.
@Service
@RequiredArgsConstructor
public class PublicLeadService {

    private static final Logger log = LoggerFactory.getLogger(PublicLeadService.class);

    private static final Pattern CAMPAIGN_CODE_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$");

    // Configurable rather than a hard-coded employee id, per design: set
    // PUBLIC_LEAD_ASSIGNEE_EMAIL in the environment to point this at a
    // different Employee (e.g. a real intake queue) without a code change.
    @Value("${crm.public-lead.assignee-email}")
    private String publicLeadAssigneeEmail;

    private final EmployeeRepository employeeRepository;
    private final IndustryRepository industryRepository;
    private final ProductRepository productRepository;
    private final BatteryRepository batteryRepository;
    private final LeadSourceMasterRepository leadSourceMasterRepository;
    private final LeadRepository leadRepository;
    private final ActivityLogService activityLogService;
    private final PublicLeadRateLimiter rateLimiter;

    @Transactional
    public PublicLeadResponse createPublicLead(PublicLeadRequest request, String clientIp) {

        rateLimiter.checkAllowed(clientIp);

        // Honeypot: a real visitor never populates this hidden field. Return
        // a normal-looking success response without creating a Lead or
        // revealing that anything was detected - see PublicLeadRequest.website.
        if (request.getWebsite() != null && !request.getWebsite().isBlank()) {

            log.warn("Public lead submission dropped (honeypot triggered) from ip={}", clientIp);

            return new PublicLeadResponse(
                    true,
                    "Thank you! Your enquiry has been received.",
                    null
            );
        }

        boolean hasProducts = request.getProducts() != null && !request.getProducts().isEmpty();
        boolean hasBatteries = request.getBatteries() != null && !request.getBatteries().isEmpty();

        if (!hasProducts && !hasBatteries) {
            throw new IllegalArgumentException("Please select at least one product or battery.");
        }

        LeadSourceMaster leadSource = resolveLeadSource(request.getSource());
        String campaignCode = sanitizeCampaignCode(request.getCampaign());

        Industry industry = industryRepository.findById(request.getIndustryId())
                .filter(i -> Boolean.TRUE.equals(i.getIsActive()))
                .orElseThrow(() -> new IllegalArgumentException("Please select a valid Industry / Business Type."));

        String otherIndustryDetail = resolveOtherIndustryDetail(industry, request.getOtherIndustryDetail());

        Employee assignee = employeeRepository.findByEmail(publicLeadAssigneeEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "Public lead assignee is not configured correctly."));

        Lead lead = Lead.builder()
                .companyName(request.getCompanyName().trim())
                .contactPerson(request.getContactPerson().trim())
                .designation(blankToNull(request.getDesignation()))
                .phone(request.getPhone().trim())
                .alternatePhone(blankToNull(request.getAlternatePhone()))
                .email(request.getEmail().trim())
                .city(request.getCity().trim())
                .state(request.getState().trim())
                .pincode(blankToNull(request.getPincode()))
                .industry(industry)
                .otherIndustryDetail(otherIndustryDetail)
                .description(request.getDescription().trim())
                .leadStatus(LeadStatus.NEW)
                .leadValidity(LeadValidity.VALID)
                .leadSource(leadSource)
                .assignedEmployee(assignee)
                .campaignCode(campaignCode)
                .build();

        applyProducts(lead, request.getProducts());
        applyBatteries(lead, request.getBatteries());

        Lead saved = leadRepository.save(lead);

        activityLogService.log(
                assignee,
                ActivityModule.LEAD, ActivityAction.CREATE,
                saved.getId(), saved.getCompanyName(),
                "Created lead via public enquiry form (source: " + leadSource.getName()
                        + (campaignCode != null ? ", campaign: " + campaignCode : "") + ")"
        );

        return new PublicLeadResponse(
                true,
                "Thank you! Your enquiry has been received. Our team will get back to you shortly.",
                saved.getId()
        );
    }

    // Maps the public form's fixed ?source= values to the two seeded
    // LeadSourceMaster rows (see config.PublicLeadSourceSeeder). Anything
    // else is rejected outright - the client never gets to name a
    // LeadSourceMaster row itself.
    private LeadSourceMaster resolveLeadSource(String rawSource) {

        String normalized = rawSource == null ? "" : rawSource.trim().toLowerCase();

        String sourceName = switch (normalized) {
            case "website" -> PublicLeadSourceSeeder.WEBSITE_SOURCE_NAME;
            case "brochure" -> PublicLeadSourceSeeder.BROCHURE_SOURCE_NAME;
            default -> throw new IllegalArgumentException("Invalid submission source.");
        };

        return leadSourceMasterRepository.findByNameIgnoreCase(sourceName)
                .orElseThrow(() -> new ResourceNotFoundException("Lead Source not found"));
    }

    // "Other / Not Listed" is a stable, seeded Industry row (see
    // PublicLeadIndustrySeeder) - the Industry master is never auto-extended
    // from public input. When that specific row is selected, the visitor's
    // typed detail is required and preserved on the Lead; for every normal
    // industry it's dropped even if the client sent one, so a stray value
    // never ends up stored against an unrelated Industry.
    private String resolveOtherIndustryDetail(Industry industry, String rawOtherIndustryDetail) {

        boolean isOtherIndustry = PublicLeadIndustrySeeder.OTHER_INDUSTRY_NAME
                .equalsIgnoreCase(industry.getName());

        if (!isOtherIndustry) {
            return null;
        }

        if (rawOtherIndustryDetail == null || rawOtherIndustryDetail.isBlank()) {
            throw new IllegalArgumentException(
                    "Please specify your industry / business type.");
        }

        return rawOtherIndustryDetail.trim();
    }

    // Campaign is free-form print-run metadata (not a whitelist), but still
    // bounded and character-validated so it can't be used to smuggle
    // anything unexpected into the leads table. An absent/blank campaign is
    // fine (plain website/brochure submissions); a present-but-malformed one
    // is rejected rather than silently truncated or stripped.
    private String sanitizeCampaignCode(String rawCampaign) {

        if (rawCampaign == null || rawCampaign.isBlank()) {
            return null;
        }

        String trimmed = rawCampaign.trim();

        if (!CAMPAIGN_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid campaign code.");
        }

        return trimmed;
    }

    // Same shape as LeadService.applyProducts, minus quantity (fixed at 1
    // for a public enquiry - see PublicLeadProductRequest). products may be
    // null/empty (a visitor can enquire about batteries only) - the
    // "at least one of products/batteries" rule is enforced earlier in
    // createPublicLead, not here.
    private void applyProducts(Lead lead, List<PublicLeadProductRequest> products) {

        if (products == null) {
            return;
        }

        for (PublicLeadProductRequest item : products) {

            Product product = productRepository.findById(item.getProductId())
                    .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                    .orElseThrow(() -> new IllegalArgumentException("Selected product is not available."));

            lead.getLeadProducts().add(
                    LeadProduct.builder()
                            .lead(lead)
                            .product(product)
                            .quantity(1)
                            .build()
            );
        }
    }

    // Battery counterpart to applyProducts above - same shape as
    // LeadService.applyBatteries, minus quantity (fixed at 1).
    private void applyBatteries(Lead lead, List<PublicLeadBatteryRequest> batteries) {

        if (batteries == null) {
            return;
        }

        for (PublicLeadBatteryRequest item : batteries) {

            Battery battery = batteryRepository.findById(item.getBatteryId())
                    .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                    .orElseThrow(() -> new IllegalArgumentException("Selected battery is not available."));

            lead.getLeadBatteries().add(
                    LeadBattery.builder()
                            .lead(lead)
                            .battery(battery)
                            .quantity(1)
                            .build()
            );
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
