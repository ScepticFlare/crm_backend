package com.compact.crm.controller;

import com.compact.crm.config.PublicLeadIndustrySeeder;
import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.Role;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.repository.BatteryRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.repository.RoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack verification (real security filter chain, real controller,
 * real service, real H2 database) that:
 *  - POST /api/leads (the internal, authenticated-only create endpoint)
 *    still rejects an unauthenticated caller, unchanged by this feature.
 *  - POST /api/public/leads is reachable without a token and creates a
 *    normal Lead visible through the same repository/list the internal CRM
 *    uses.
 *  - A public caller cannot override leadStatus/assignedEmployee/leadSource
 *    by sending extra JSON fields with those names - PublicLeadRequest has
 *    no such properties for Jackson to bind onto, so they're silently
 *    ignored and the server-side defaults always win.
 *  - GET /api/public/industries and /api/public/products (needed to
 *    populate the public form's dropdowns) are reachable without a token.
 */
// Raises the rate limit well above what this class's handful of legitimate
// POST /api/public/leads calls could ever hit - PublicLeadRateLimiter is a
// singleton bean shared across every test method in this class (one Spring
// context), and per-IP rate limiting is already covered on its own in
// PublicLeadServiceIntegrationTest.perIpRateLimit_blocksSubmissionsAboveTheConfiguredMax.
// @Transactional: each test method runs (and rolls back) in its own
// transaction so repeated @BeforeEach inserts (Industry/Product names are
// unique) never collide across test methods sharing this class's one
// Spring context, and it keeps the persistence context open long enough for
// saved.getLeadProducts() (lazy) to be read back after the MockMvc call
// returns.
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "crm.public-lead.rate-limit.max-requests=1000")
@Transactional
class PublicLeadControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BatteryRepository batteryRepository;
    @Autowired private LeadRepository leadRepository;

    private Industry industry;
    private Industry otherIndustry;
    private Product product;
    private Battery battery;

    @BeforeEach
    void setUp() {

        // crm.public-lead.assignee-email is "test-admin@example.com" in
        // src/test/resources/application.properties.
        if (employeeRepository.findByEmail("test-admin@example.com").isEmpty()) {

            Role adminRole = roleRepository.findAll().stream()
                    .filter(r -> "ADMIN".equals(r.getName()))
                    .findFirst()
                    .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").rank(100).build()));

            employeeRepository.save(Employee.builder()
                    .name("Test Admin").email("test-admin@example.com")
                    .phone("9000000099").password("x").role(adminRole).build());
        }

        industry = industryRepository.save(Industry.builder().name("Public Test Industry").build());

        // Already seeded at application startup by PublicLeadIndustrySeeder
        // (committed outside this test's transaction) - look it up rather
        // than re-inserting, which would collide with the unique name
        // constraint.
        otherIndustry = industryRepository.findByNameIgnoreCase(PublicLeadIndustrySeeder.OTHER_INDUSTRY_NAME)
                .orElseThrow();

        product = productRepository.save(Product.builder().name("Public Test Product").build());
        battery = batteryRepository.save(Battery.builder().name("Public Test Battery").build());
    }

    @Test
    void unauthenticated_cannotCreateLeadViaInternalEndpoint() throws Exception {

        mockMvc.perform(post("/api/leads")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_canReachPublicIndustriesAndProducts() throws Exception {

        mockMvc.perform(get("/api/public/industries")).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/batteries")).andExpect(status().isOk());
    }

    @Test
    void unauthenticated_canSubmitPublicLead_andItAppearsAsANormalLead() throws Exception {

        Map<String, Object> payload = validPayload("website", null);

        String responseBody = mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        Long referenceId = objectMapper.readTree(responseBody).get("referenceId").asLong();

        var saved = leadRepository.findById(referenceId).orElseThrow();

        assertThat(saved.getLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(saved.getAssignedEmployee().getEmail()).isEqualTo("test-admin@example.com");
        assertThat(saved.getLeadSource().getName()).isEqualTo("Website Form");
        assertThat(saved.getLeadProducts()).hasSize(1);
    }

    @Test
    void publicCaller_cannotOverrideStatusAssigneeOrSource() throws Exception {

        Map<String, Object> payload = new java.util.HashMap<>(validPayload("website", null));

        // None of these are real PublicLeadRequest fields - if they were
        // ever bound, this would prove the whitelist DTO is broken.
        payload.put("leadStatus", "WON");
        payload.put("assignedEmployeeId", 999999);
        payload.put("leadSourceId", 999999);
        payload.put("leadValidity", "INVALID");
        payload.put("finalRemarks", "attacker-controlled");

        String responseBody = mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long referenceId = objectMapper.readTree(responseBody).get("referenceId").asLong();
        var saved = leadRepository.findById(referenceId).orElseThrow();

        assertThat(saved.getLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(saved.getAssignedEmployee().getEmail()).isEqualTo("test-admin@example.com");
        assertThat(saved.getLeadSource().getName()).isEqualTo("Website Form");
        assertThat(saved.getFinalRemarks()).isNull();
    }

    @Test
    void brochureSubmissionWithCampaign_isStoredWithCampaignCode() throws Exception {

        Map<String, Object> payload = validPayload("brochure", "enterprise-brochure-2026");

        String responseBody = mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long referenceId = objectMapper.readTree(responseBody).get("referenceId").asLong();
        var saved = leadRepository.findById(referenceId).orElseThrow();

        assertThat(saved.getLeadSource().getName()).isEqualTo("Brochure QR");
        assertThat(saved.getCampaignCode()).isEqualTo("enterprise-brochure-2026");
    }

    @Test
    void honeypotFilled_returnsSuccessButCreatesNoLead() throws Exception {

        Map<String, Object> payload = new java.util.HashMap<>(validPayload("website", null));
        payload.put("website", "http://bot.example.com");

        long before = leadRepository.count();

        mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceId").doesNotExist());

        assertThat(leadRepository.count()).isEqualTo(before);
    }

    @Test
    void otherIndustryWithDetail_succeeds() throws Exception {

        Map<String, Object> payload = new java.util.HashMap<>(validPayload("website", null));
        payload.put("industryId", otherIndustry.getId());
        payload.put("otherIndustryDetail", "Artisanal cheese exporter");

        String responseBody = mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        Long referenceId = objectMapper.readTree(responseBody).get("referenceId").asLong();
        var saved = leadRepository.findById(referenceId).orElseThrow();

        assertThat(saved.getIndustry().getId()).isEqualTo(otherIndustry.getId());
        assertThat(saved.getIndustry().getName()).isEqualTo(PublicLeadIndustrySeeder.OTHER_INDUSTRY_NAME);
        assertThat(saved.getOtherIndustryDetail()).isEqualTo("Artisanal cheese exporter");
    }

    @Test
    void otherIndustryWithoutDetail_isRejected() throws Exception {

        Map<String, Object> payload = new java.util.HashMap<>(validPayload("website", null));
        payload.put("industryId", otherIndustry.getId());
        // otherIndustryDetail deliberately omitted.

        mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void batteryOnlySubmission_succeeds() throws Exception {

        Map<String, Object> payload = new java.util.HashMap<>(validPayload("website", null));
        payload.remove("products");
        payload.put("batteries", List.of(Map.of("batteryId", battery.getId())));

        String responseBody = mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        Long referenceId = objectMapper.readTree(responseBody).get("referenceId").asLong();
        var saved = leadRepository.findById(referenceId).orElseThrow();

        assertThat(saved.getLeadProducts()).isEmpty();
        assertThat(saved.getLeadBatteries()).hasSize(1);
        assertThat(saved.getLeadBatteries().get(0).getBattery().getId()).isEqualTo(battery.getId());
    }

    @Test
    void productAndBatterySubmission_succeeds() throws Exception {

        Map<String, Object> payload = new java.util.HashMap<>(validPayload("website", null));
        payload.put("batteries", List.of(Map.of("batteryId", battery.getId())));

        String responseBody = mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long referenceId = objectMapper.readTree(responseBody).get("referenceId").asLong();
        var saved = leadRepository.findById(referenceId).orElseThrow();

        assertThat(saved.getLeadProducts()).hasSize(1);
        assertThat(saved.getLeadBatteries()).hasSize(1);
    }

    @Test
    void invalidBatteryId_isRejected() throws Exception {

        Map<String, Object> payload = new java.util.HashMap<>(validPayload("website", null));
        payload.remove("products");
        payload.put("batteries", List.of(Map.of("batteryId", 999999)));

        mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void neitherProductNorBattery_isRejected() throws Exception {

        Map<String, Object> payload = new java.util.HashMap<>(validPayload("website", null));
        payload.remove("products");

        mockMvc.perform(post("/api/public/leads")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is4xxClientError());
    }

    private Map<String, Object> validPayload(String source, String campaign) {

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("companyName", "Acme Corp");
        payload.put("contactPerson", "Jane Doe");
        payload.put("phone", "9876543210");
        payload.put("email", "jane@acme.com");
        payload.put("city", "Pune");
        payload.put("state", "Maharashtra");
        payload.put("industryId", industry.getId());
        payload.put("description", "Need a quote for 5 units.");
        payload.put("products", List.of(Map.of("productId", product.getId())));
        payload.put("source", source);

        if (campaign != null) {
            payload.put("campaign", campaign);
        }

        return payload;
    }
}
