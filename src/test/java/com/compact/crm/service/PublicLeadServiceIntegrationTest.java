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
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.Role;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.repository.BatteryRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.security.PublicLeadRateLimiter;
import com.compact.crm.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real end-to-end verification of PublicLeadService against a real H2
 * database with the real ActivityLogService/PublicLeadRateLimiter beans -
 * the same @DataJpaTest + @Import convention as
 * LeadDeletionCascadeIntegrationTest. Covers the design decisions from the
 * public-lead-form task: NEW status, Test Admin assignment, source
 * resolution, campaignCode storage, product relationship, honeypot
 * drop-silently behavior, and per-IP rate limiting - never the HTTP/security
 * layer itself (see PublicLeadControllerSecurityTest for that).
 */
@DataJpaTest
@Import({PublicLeadService.class, ActivityLogService.class, PublicLeadRateLimiter.class,
        CurrentUserService.class, AccessControlService.class})
class PublicLeadServiceIntegrationTest {

    @Autowired private PublicLeadService publicLeadService;
    @Autowired private LeadRepository leadRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BatteryRepository batteryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private RoleRepository roleRepository;

    private Employee testAdmin;
    private Industry industry;
    private Industry otherIndustry;
    private Product product;
    private Battery battery;
    private Battery secondBattery;

    @BeforeEach
    void setUp() {

        ReflectionTestUtils.setField(
                publicLeadService, "publicLeadAssigneeEmail", "test-admin@example.com");

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());

        testAdmin = employeeRepository.save(Employee.builder()
                .name("Test Admin").email("test-admin@example.com")
                .phone("9000000099").password("x").role(adminRole).build());

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        otherIndustry = industryRepository.save(
                Industry.builder().name(PublicLeadIndustrySeeder.OTHER_INDUSTRY_NAME).build());
        product = productRepository.save(Product.builder().name("Online UPS").build());
        battery = batteryRepository.save(Battery.builder().name("12V 7Ah").build());
        secondBattery = batteryRepository.save(Battery.builder().name("12V 9Ah").build());

        leadSourceMasterRepository.save(
                LeadSourceMaster.builder().name(PublicLeadSourceSeeder.WEBSITE_SOURCE_NAME).build());
        leadSourceMasterRepository.save(
                LeadSourceMaster.builder().name(PublicLeadSourceSeeder.BROCHURE_SOURCE_NAME).build());
    }

    private PublicLeadRequest validRequest(String source, String campaign) {

        PublicLeadRequest request = new PublicLeadRequest();
        request.setCompanyName("Acme Corp");
        request.setContactPerson("Jane Doe");
        request.setPhone("9876543210");
        request.setEmail("jane@acme.com");
        request.setCity("Pune");
        request.setState("Maharashtra");
        request.setIndustryId(industry.getId());
        request.setDescription("Need a quote for 5 units.");
        request.setProducts(List.of(new PublicLeadProductRequest(product.getId())));
        request.setSource(source);
        request.setCampaign(campaign);

        return request;
    }

    @Test
    void websiteSubmission_createsNormalNewLeadAssignedToTestAdmin() {

        PublicLeadResponse response = publicLeadService.createPublicLead(
                validRequest("website", null), "10.0.0.1");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getReferenceId()).isNotNull();

        Lead saved = leadRepository.findById(response.getReferenceId()).orElseThrow();

        assertThat(saved.getLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(saved.getAssignedEmployee().getId()).isEqualTo(testAdmin.getId());
        assertThat(saved.getLeadSource().getName()).isEqualTo(PublicLeadSourceSeeder.WEBSITE_SOURCE_NAME);
        assertThat(saved.getCampaignCode()).isNull();
        assertThat(saved.getLeadProducts()).hasSize(1);
        assertThat(saved.getLeadProducts().get(0).getProduct().getId()).isEqualTo(product.getId());
        assertThat(saved.getLeadProducts().get(0).getQuantity()).isEqualTo(1);
        assertThat(saved.getOtherIndustryDetail()).isNull();
    }

    @Test
    void otherIndustryWithDetail_createsLeadOnStableOtherIndustry() {

        PublicLeadRequest request = validRequest("website", null);
        request.setIndustryId(otherIndustry.getId());
        request.setOtherIndustryDetail("Artisanal cheese exporter");

        PublicLeadResponse response = publicLeadService.createPublicLead(request, "10.0.0.10");

        Lead saved = leadRepository.findById(response.getReferenceId()).orElseThrow();

        assertThat(saved.getIndustry().getId()).isEqualTo(otherIndustry.getId());
        assertThat(saved.getIndustry().getName()).isEqualTo(PublicLeadIndustrySeeder.OTHER_INDUSTRY_NAME);
        assertThat(saved.getOtherIndustryDetail()).isEqualTo("Artisanal cheese exporter");
    }

    @Test
    void otherIndustryWithoutDetail_isRejected() {

        PublicLeadRequest request = validRequest("website", null);
        request.setIndustryId(otherIndustry.getId());
        // otherIndustryDetail deliberately left blank.

        assertThatThrownBy(() -> publicLeadService.createPublicLead(request, "10.0.0.11"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalIndustry_ignoresStrayOtherIndustryDetail() {

        // A client could send otherIndustryDetail alongside a normal
        // industryId (e.g. a stale/tampered form state) - it must never be
        // stored against a real Industry.
        PublicLeadRequest request = validRequest("website", null);
        request.setOtherIndustryDetail("Should be ignored");

        PublicLeadResponse response = publicLeadService.createPublicLead(request, "10.0.0.12");

        Lead saved = leadRepository.findById(response.getReferenceId()).orElseThrow();

        assertThat(saved.getIndustry().getId()).isEqualTo(industry.getId());
        assertThat(saved.getOtherIndustryDetail()).isNull();
    }

    @Test
    void brochureSubmission_storesSourceAndCampaignCode() {

        PublicLeadResponse response = publicLeadService.createPublicLead(
                validRequest("brochure", "enterprise-brochure-2026"), "10.0.0.2");

        Lead saved = leadRepository.findById(response.getReferenceId()).orElseThrow();

        assertThat(saved.getLeadSource().getName()).isEqualTo(PublicLeadSourceSeeder.BROCHURE_SOURCE_NAME);
        assertThat(saved.getCampaignCode()).isEqualTo("enterprise-brochure-2026");
    }

    @Test
    void campaignParameterAlone_doesNotImplyBrochureSource() {

        // source=website with a campaign param present must still resolve
        // to the Website source, never inferred as brochure just because a
        // campaign value was supplied.
        PublicLeadResponse response = publicLeadService.createPublicLead(
                validRequest("website", "some-campaign"), "10.0.0.3");

        Lead saved = leadRepository.findById(response.getReferenceId()).orElseThrow();

        assertThat(saved.getLeadSource().getName()).isEqualTo(PublicLeadSourceSeeder.WEBSITE_SOURCE_NAME);
        assertThat(saved.getCampaignCode()).isEqualTo("some-campaign");
    }

    @Test
    void invalidSource_isRejected() {

        assertThatThrownBy(() -> publicLeadService.createPublicLead(
                validRequest("not-a-real-source", null), "10.0.0.4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCampaignFormat_isRejected() {

        assertThatThrownBy(() -> publicLeadService.createPublicLead(
                validRequest("brochure", "bad campaign!"), "10.0.0.5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inactiveProduct_isRejected() {

        // Product.onCreate() forces isActive=true on insert (same as the
        // real app - a Product is always created active, only deactivated
        // afterward) - so simulate deactivation as a separate update, same
        // as ProductService.deactivate does.
        Product inactive = productRepository.save(Product.builder().name("Discontinued Unit").build());
        inactive.setIsActive(false);
        inactive = productRepository.save(inactive);

        PublicLeadRequest request = validRequest("website", null);
        request.setProducts(List.of(new PublicLeadProductRequest(inactive.getId())));

        assertThatThrownBy(() -> publicLeadService.createPublicLead(request, "10.0.0.6"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batteryOnlySubmission_succeeds() {

        PublicLeadRequest request = validRequest("website", null);
        request.setProducts(null);
        request.setBatteries(List.of(new PublicLeadBatteryRequest(battery.getId())));

        PublicLeadResponse response = publicLeadService.createPublicLead(request, "10.0.0.13");

        Lead saved = leadRepository.findById(response.getReferenceId()).orElseThrow();

        assertThat(saved.getLeadProducts()).isEmpty();
        assertThat(saved.getLeadBatteries()).hasSize(1);
        assertThat(saved.getLeadBatteries().get(0).getBattery().getId()).isEqualTo(battery.getId());
        assertThat(saved.getLeadBatteries().get(0).getQuantity()).isEqualTo(1);
    }

    @Test
    void productAndBatterySubmission_succeeds() {

        PublicLeadRequest request = validRequest("website", null);
        request.setBatteries(List.of(new PublicLeadBatteryRequest(battery.getId())));

        PublicLeadResponse response = publicLeadService.createPublicLead(request, "10.0.0.14");

        Lead saved = leadRepository.findById(response.getReferenceId()).orElseThrow();

        assertThat(saved.getLeadProducts()).hasSize(1);
        assertThat(saved.getLeadBatteries()).hasSize(1);
    }

    @Test
    void multipleBatteries_areAllSaved() {

        PublicLeadRequest request = validRequest("website", null);
        request.setProducts(null);
        request.setBatteries(List.of(
                new PublicLeadBatteryRequest(battery.getId()),
                new PublicLeadBatteryRequest(secondBattery.getId())
        ));

        PublicLeadResponse response = publicLeadService.createPublicLead(request, "10.0.0.15");

        Lead saved = leadRepository.findById(response.getReferenceId()).orElseThrow();

        assertThat(saved.getLeadBatteries()).hasSize(2);
        assertThat(saved.getLeadBatteries())
                .extracting(leadBattery -> leadBattery.getBattery().getId())
                .containsExactlyInAnyOrder(battery.getId(), secondBattery.getId());
    }

    @Test
    void inactiveBattery_isRejected() {

        Battery inactive = batteryRepository.save(Battery.builder().name("Discontinued Battery").build());
        inactive.setIsActive(false);
        inactive = batteryRepository.save(inactive);

        PublicLeadRequest request = validRequest("website", null);
        request.setProducts(null);
        request.setBatteries(List.of(new PublicLeadBatteryRequest(inactive.getId())));

        assertThatThrownBy(() -> publicLeadService.createPublicLead(request, "10.0.0.16"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neitherProductNorBattery_isRejected() {

        PublicLeadRequest request = validRequest("website", null);
        request.setProducts(null);

        assertThatThrownBy(() -> publicLeadService.createPublicLead(request, "10.0.0.17"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyProductAndBatteryLists_areRejected() {

        // Same rule, but with empty lists rather than null - both must be
        // treated the same as "nothing selected".
        PublicLeadRequest request = validRequest("website", null);
        request.setProducts(List.of());
        request.setBatteries(List.of());

        assertThatThrownBy(() -> publicLeadService.createPublicLead(request, "10.0.0.18"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void honeypotFilled_silentlyDropsSubmissionWithoutCreatingLead() {

        PublicLeadRequest request = validRequest("website", null);
        request.setWebsite("http://spam.example.com");

        long before = leadRepository.count();

        PublicLeadResponse response = publicLeadService.createPublicLead(request, "10.0.0.7");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getReferenceId()).isNull();
        assertThat(leadRepository.count()).isEqualTo(before);
    }

    @Test
    void assigneeNotConfigured_failsLoudlyRatherThanMisassigning() {

        ReflectionTestUtils.setField(
                publicLeadService, "publicLeadAssigneeEmail", "no-such-employee@example.com");

        assertThatThrownBy(() -> publicLeadService.createPublicLead(
                validRequest("website", null), "10.0.0.8"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void perIpRateLimit_blocksSubmissionsAboveTheConfiguredMax() {

        String ip = "10.0.0.9";
        int allowed = 0;

        for (int i = 0; i < 10; i++) {

            try {
                publicLeadService.createPublicLead(validRequest("website", null), ip);
                allowed++;
            } catch (TooManyRequestsException ex) {
                break;
            }
        }

        // Default max-requests is 5 (crm.public-lead.rate-limit.max-requests) -
        // exact count isn't the point, just that the limiter actually kicks
        // in well before 10 rapid submissions from the same IP succeed.
        assertThat(allowed).isLessThan(10);
    }
}
