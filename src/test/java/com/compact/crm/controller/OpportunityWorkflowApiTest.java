package com.compact.crm.controller;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.repository.SalesStageRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.compact.crm.util.BusinessClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level check (real security chain, JSON binding, controllers, H2) of
 * the Opportunity workflow API exactly as the frontend uses it: request
 * bodies, validation messages shown to the user, and the response fields
 * the UI reads (closingDateExpired, noNextAction, effectiveSubStatus,
 * postponedReminderDue...).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpportunityWorkflowApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private SalesStageRepository salesStageRepository;
    @Autowired private com.compact.crm.repository.FollowUpRepository followUpRepository;
    @Autowired private com.compact.crm.repository.ActivityTypeRepository activityTypeRepository;

    private String token;
    private Employee admin;
    private Industry industry;
    private LeadSourceMaster leadSource;
    private com.compact.crm.entity.Product product;
    private com.compact.crm.entity.Battery battery;
    @Autowired private com.compact.crm.repository.ProductRepository productRepository;
    @Autowired private com.compact.crm.repository.BatteryRepository batteryRepository;

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.findAll().stream()
                .filter(r -> "ADMIN".equals(r.getName())).findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").rank(100).build()));

        Map<String, Permission> permissions = new HashMap<>();

        for (String code : List.of("LEAD_MANAGE", "LEAD_VIEW", "OPPORTUNITY_MANAGE", "OPPORTUNITY_VIEW", "ACTIVITY_VIEW", "FOLLOWUP_VIEW")) {

            Permission p = permissionRepository.findAll().stream()
                    .filter(x -> code.equals(x.getCode())).findFirst()
                    .orElseGet(() -> permissionRepository.save(Permission.builder().code(code).build()));

            permissions.put(code, p);

            boolean granted = rolePermissionRepository.findAll().stream()
                    .anyMatch(rp -> rp.getRole().getId().equals(adminRole.getId())
                            && rp.getPermission().getId().equals(p.getId()));

            if (!granted) {
                rolePermissionRepository.save(RolePermission.builder()
                        .role(adminRole).permission(p).scope(Scope.ALL).build());
            }
        }

        admin = employeeRepository.save(Employee.builder()
                .name("Api Admin").email("api-admin@test.com").phone("9200000001")
                .password("x").role(adminRole).build());

        token = "Bearer " + jwtService.generateToken(new UserPrincipal(admin));

        industry = industryRepository.save(Industry.builder().name("Api Industry").build());
        leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Api Source").build());
        product = productRepository.save(com.compact.crm.entity.Product.builder().name("Api Product").build());
        battery = batteryRepository.save(com.compact.crm.entity.Battery.builder().name("Api Battery").build());

    }

    // A VALID lead that already has a qualifying next action (pending, future,
    // described), as an active opportunity requires.
    private Lead lead(String company) {

        Lead lead = leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("C").phone("1234567890")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.VALID)
                .assignedEmployee(admin).build());

        followUpRepository.save(com.compact.crm.entity.FollowUp.builder()
                .lead(lead).employee(admin).activityType(apiActivityType())
                .status(com.compact.crm.enums.FollowUpStatus.PENDING)
                .scheduledDate(java.time.LocalDateTime.now().plusDays(10))
                .remarks("Call to confirm the requirement").build());

        return lead;
    }

    private com.compact.crm.entity.ActivityType apiActivityType() {
        return activityTypeRepository.findAll().stream().findFirst().orElseGet(() ->
                activityTypeRepository.save(com.compact.crm.entity.ActivityType.builder().name("Call").build()));
    }

    private String body(String stage, String closing, String extraJson) {

        return "{\"title\":\"Deal\",\"productValue\":1000,"
                + (closing == null ? "" : "\"expectedClosingDate\":\"" + closing + "\",")
                + "\"salesStage\":\"" + stage + "\",\"leadValidity\":\"VALID\""
                + (extraJson == null ? "" : "," + extraJson) + "}";
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void convert_withoutClosingDate_returns400WithAMessageTheUserCanRead() throws Exception {

        Lead lead = lead("NoDateApi");

        mockMvc.perform(post("/api/opportunities/convert/" + lead.getId())
                        .header("Authorization", token).contentType("application/json")
                        .content(body("IN PROGRESS", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Expected closing date is required")));

        assertThat(opportunityRepository.existsByLeadId(lead.getId())).isFalse();
    }

    @Test
    void fullWorkflow_throughTheHttpApi() throws Exception {

        String future = BusinessClock.today().plusDays(20).toString();
        Lead lead = lead("FlowApi");
        // Convert -> IN PROGRESS with a sub-status. The lead has a qualifying next action, so it saves with none missing.
        // Convert -> IN PROGRESS with a sub-status. No follow-up exists: still saves, but is flagged.
        MvcResult created = mockMvc.perform(post("/api/opportunities/convert/" + lead.getId())
                        .header("Authorization", token).contentType("application/json")
                        .content(body("IN PROGRESS", future, "\"subStatus\":\"QUOTATION_SENT\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subStatus").value("QUOTATION_SENT"))
                .andExpect(jsonPath("$.effectiveSubStatus").value("QUOTATION_SENT"))
                .andExpect(jsonPath("$.salesStage.name").value("IN PROGRESS"))
                .andExpect(jsonPath("$.closingDateExpired").value(false))
                .andExpect(jsonPath("$.noNextAction").value(false))
                .andReturn();

        long id = json(created).get("id").asLong();

        // Sub-status with a non IN PROGRESS stage -> 400.
        mockMvc.perform(put("/api/opportunities/" + id)
                        .header("Authorization", token).contentType("application/json")
                        .content(body("WON", future, "\"subStatus\":\"NEGOTIATION\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("only be set when the stage is In Progress")));

        // LOST without a reason -> 400; with a reason -> saved and visible on read.
        mockMvc.perform(put("/api/opportunities/" + id)
                        .header("Authorization", token).contentType("application/json")
                        .content(body("LOST", future, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reason")));

        mockMvc.perform(put("/api/opportunities/" + id)
                        .header("Authorization", token).contentType("application/json")
                        .content(body("LOST", future, "\"lostReason\":\"Chose a competitor\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lostReason").value("Chose a competitor"))
                .andExpect(jsonPath("$.subStatus").doesNotExist());

        // POSTPONED with today's review date -> reminder due; OK keeps the stage; resume changes it.
        String today = BusinessClock.today().toString();

        mockMvc.perform(put("/api/opportunities/" + id)
                        .header("Authorization", token).contentType("application/json")
                        .content(body("POSTPONED", future, "\"postponedReviewDate\":\"" + today + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postponedReminderDue").value(true))
                .andExpect(jsonPath("$.lostReason").doesNotExist());

        mockMvc.perform(get("/api/opportunities/workflow-alerts").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postponedReminderCount").value(1))
                .andExpect(jsonPath("$.postponedReminders[0].id").value(id));

        mockMvc.perform(post("/api/opportunities/" + id + "/postponed-reminder/acknowledge")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postponedReminderDue").value(false))
                .andExpect(jsonPath("$.salesStage.name").value("POSTPONED"));

        mockMvc.perform(get("/api/opportunities/" + id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postponedReminderDue").value(false))
                .andExpect(jsonPath("$.salesStage.name").value("POSTPONED"));

        mockMvc.perform(post("/api/opportunities/" + id + "/return-to-in-progress")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"expectedClosingDate\":\"" + future + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesStage.name").value("IN PROGRESS"));

        // History: field-level rows with old/new value are exposed to the existing history API.
        MvcResult history = mockMvc.perform(get("/api/activity")
                        .header("Authorization", token)
                        .param("module", "OPPORTUNITY").param("entityId", String.valueOf(id))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode entries = json(history).get("content");

        boolean sawStageChange = false;
        boolean sawLostReason = false;

        for (JsonNode e : entries) {

            if ("salesStage".equals(e.path("fieldName").asText())
                    && "POSTPONED".equals(e.path("oldValue").asText())
                    && "IN PROGRESS".equals(e.path("newValue").asText())) {
                sawStageChange = true;
                assertThat(e.path("employeeName").asText()).isEqualTo("Api Admin");
                assertThat(e.path("createdAt").asText()).isNotBlank();
            }

            if ("lostReason".equals(e.path("fieldName").asText())
                    && "Chose a competitor".equals(e.path("newValue").asText())) {
                sawLostReason = true;
            }
        }

        assertThat(sawStageChange).isTrue();
        assertThat(sawLostReason).isTrue();
    }

    @Test
    void legacyOpportunityWithoutNewFields_loadsThroughTheApi() throws Exception {

        // A record as old code wrote it: no closing date, no sub-status, no reason.
        SalesStage lost = salesStageRepository.findByNameIgnoreCase("LOST").orElseThrow();
        Lead lead = lead("LegacyApi");

        Long id = opportunityRepository.save(com.compact.crm.entity.Opportunity.builder()
                .title("Old lost deal").productValue(10.0).salesStage(lost)
                .leadValidity(LeadValidity.VALID).lead(lead).build()).getId();

        mockMvc.perform(get("/api/opportunities/" + id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Old lost deal"))
                .andExpect(jsonPath("$.lostReason").doesNotExist())
                .andExpect(jsonPath("$.expectedClosingDate").doesNotExist())
                .andExpect(jsonPath("$.closingDateExpired").value(false))
                .andExpect(jsonPath("$.noNextAction").value(false));

        mockMvc.perform(get("/api/opportunities").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").exists());
    }

    @Test
    void invalidLead_conversionIsRejectedOverHttp_withAClearMessage() throws Exception {

        Lead invalid = leadRepository.save(Lead.builder()
                .companyName("Invalid Api Co").contactPerson("C").phone("1234567890")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.INVALID)
                .assignedEmployee(admin).build());

        String future = BusinessClock.today().plusDays(20).toString();

        mockMvc.perform(post("/api/opportunities/convert/" + invalid.getId())
                        .header("Authorization", token).contentType("application/json")
                        .content(body("IN PROGRESS", future, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Only VALID leads can be converted")));

        assertThat(opportunityRepository.existsByLeadId(invalid.getId())).isFalse();

        // a VALID lead converts, a repeat of the same conversion is a clean 400
        Lead valid = lead("Valid Api Co");

        mockMvc.perform(post("/api/opportunities/convert/" + valid.getId())
                        .header("Authorization", token).contentType("application/json")
                        .content(body("IN PROGRESS", future, null)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/opportunities/convert/" + valid.getId())
                        .header("Authorization", token).contentType("application/json")
                        .content(body("IN PROGRESS", future, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already been converted")));
        // the lead detail response tells the UI this lead is now converted
        mockMvc.perform(get("/api/leads/" + valid.getId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converted").value(true));

        mockMvc.perform(get("/api/leads/" + invalid.getId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converted").value(false));

    }

    @Test
    void workflowStillWorksAfterAnAdminRenamesTheCoreStagesOverHttp() throws Exception {

        String future = BusinessClock.today().plusDays(20).toString();

        // an Admin renames IN PROGRESS and LOST through the real endpoint
        for (var rename : java.util.Map.of(
                com.compact.crm.enums.SalesStageType.IN_PROGRESS, "Working",
                com.compact.crm.enums.SalesStageType.LOST, "Closed Lost").entrySet()) {

            Long stageId = salesStageRepository.findByStageType(rename.getKey()).get(0).getId();

            mockMvc.perform(put("/api/sales-stages/" + stageId)
                            .header("Authorization", token).contentType("application/json")
                            .content("{\"name\":\"" + rename.getValue() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stageType").value(rename.getKey().name()));
        }

        Lead lead = lead("RenamedApi");

        MvcResult created = mockMvc.perform(post("/api/opportunities/convert/" + lead.getId())
                        .header("Authorization", token).contentType("application/json")
                        .content(body("Working", future, "\"subStatus\":\"NEGOTIATION\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesStage.stageType").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.salesStage.workflowActive").value(true))
                .andExpect(jsonPath("$.subStatus").value("NEGOTIATION"))
                .andReturn();

        long id = json(created).get("id").asLong();

        // LOST (renamed) still demands a reason
        mockMvc.perform(put("/api/opportunities/" + id)
                        .header("Authorization", token).contentType("application/json")
                        .content(body("Closed Lost", null, null)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/opportunities/" + id)
                        .header("Authorization", token).contentType("application/json")
                        .content(body("Closed Lost", null, "\"lostReason\":\"Went elsewhere\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesStage.workflowActive").value(false));

        // the Lost page (filters by type, not name) lists it
        mockMvc.perform(get("/api/opportunities/lost").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id));
    }

    // ---------------------------------------------------------------- simplified lead workflow over HTTP

    private String leadBody(String company, String status, String reason) {

        return "{\"companyName\":\"" + company + "\",\"contactPerson\":\"C\",\"designation\":\"M\","
                + "\"phone\":\"9" + String.format("%09d", Math.abs(company.hashCode()) % 1000000000) + "\","
                + "\"city\":\"Pune\",\"state\":\"MH\",\"industryId\":" + industry.getId()
                + ",\"leadSourceId\":" + leadSource.getId() + ",\"leadStatus\":\"" + status + "\","
                + "\"assignedEmployeeId\":" + admin.getId()
                + ",\"products\":[{\"productId\":" + product.getId() + ",\"quantity\":1}]"
                + ",\"batteries\":[{\"batteryId\":" + battery.getId() + ",\"quantity\":1}]"
                + (reason == null ? "" : ",\"invalidReason\":\"" + reason + "\"") + "}";
    }

    @Test
    void invalidLead_needsAReasonOverHttp_andAnOlderStatusCannotBeChosen() throws Exception {

        mockMvc.perform(post("/api/leads").header("Authorization", token).contentType("application/json")
                        .content(leadBody("Http Invalid", "INVALID", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reason this lead is invalid")));

        mockMvc.perform(post("/api/leads").header("Authorization", token).contentType("application/json")
                        .content(leadBody("Http Invalid", "INVALID", "Spam enquiry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadStatus").value("INVALID"))
                .andExpect(jsonPath("$.effectiveLeadStatus").value("INVALID"))
                .andExpect(jsonPath("$.invalidReason").value("Spam enquiry"));

        mockMvc.perform(post("/api/leads").header("Authorization", token).contentType("application/json")
                        .content(leadBody("Http Dropped", "DROPPED", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("New, Valid or Invalid")));
    }

    @Test
    void leadJson_exposesTheDisplayStatusLegacyValueAndConversionReason() throws Exception {

        Lead legacy = leadRepository.save(Lead.builder()
                .companyName("Http Legacy").contactPerson("C").phone("1234567890")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.QUOTATION_SENT).leadValidity(LeadValidity.VALID)
                .assignedEmployee(admin).build());

        mockMvc.perform(get("/api/leads/" + legacy.getId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadStatus").value("QUOTATION_SENT"))        // stored value untouched
                .andExpect(jsonPath("$.effectiveLeadStatus").value("NEW"))
                .andExpect(jsonPath("$.legacyStatus").value("QUOTATION_SENT"))
                .andExpect(jsonPath("$.converted").value(false))
                .andExpect(jsonPath("$.conversionBlockedReason")
                        .value(org.hamcrest.Matchers.containsString("older status QUOTATION SENT")));

        // list rows carry the same computed fields
        mockMvc.perform(get("/api/leads").header("Authorization", token).param("statusGroup", "NEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].effectiveLeadStatus").value("NEW"))
                .andExpect(jsonPath("$.content[0].converted").value(false));

        // and the legacy lead can be converted only after it is made VALID
        String future = BusinessClock.today().plusDays(20).toString();

        mockMvc.perform(post("/api/opportunities/convert/" + legacy.getId())
                        .header("Authorization", token).contentType("application/json")
                        .content(body("IN PROGRESS", future, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Only VALID leads")));
    }

    @Test
    void duplicatesEndpoint_warnsWithoutBlocking() throws Exception {

        mockMvc.perform(post("/api/leads").header("Authorization", token).contentType("application/json")
                        .content(leadBody("Dup Corp", "NEW", null)))
                .andExpect(status().isOk());

        // the same company again is still ALLOWED (warning only)...
        mockMvc.perform(post("/api/leads").header("Authorization", token).contentType("application/json")
                        .content(leadBody("Dup Corp", "NEW", null).replaceAll("\"phone\":\"[0-9]+\"", "\"phone\":\"9111122222\"")))
                .andExpect(status().isOk());

        // ...but the check reports it
        mockMvc.perform(get("/api/leads/duplicates").header("Authorization", token).param("company", "dup corp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(2))
                .andExpect(jsonPath("$.matches[0].matchedOn[0]").value("company"))
                .andExpect(jsonPath("$.hiddenCount").value(0));

        mockMvc.perform(get("/api/leads/duplicates").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(0));

        mockMvc.perform(get("/api/leads/duplicates").param("company", "Dup Corp"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void managementSummary_isReachableWithAToken_notWithoutOne() throws Exception {

        mockMvc.perform(get("/api/management/summary").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funnel.leadsReceived").exists())
                .andExpect(jsonPath("$.pipeline.openCount").exists())
                .andExpect(jsonPath("$.closingSoon.length()").value(3))
                .andExpect(jsonPath("$.notes.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(get("/api/management/summary").param("from", "2026-01-01").param("to", "2026-01-31")
                        .header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/management/summary"))
                .andExpect(status().is4xxClientError());
    }
}
