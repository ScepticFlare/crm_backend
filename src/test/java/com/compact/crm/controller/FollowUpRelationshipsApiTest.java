package com.compact.crm.controller;

import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which record a follow-up belongs to, over HTTP with the real security chain:
 * creation for leads and opportunities, the "related" block every response
 * carries (an opportunity follow-up has no lead_id - its company comes from the
 * opportunity's lead), editing, same-named records, RBAC, the picker endpoint
 * and historical rows with missing links. H2, rolled back after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FollowUpRelationshipsApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private SalesStageRepository salesStageRepository;

    private static final List<String> CODES = List.of("LEAD_VIEW", "LEAD_MANAGE", "OPPORTUNITY_VIEW",
            "OPPORTUNITY_MANAGE", "FOLLOWUP_VIEW", "FOLLOWUP_MANAGE", "FOLLOWUP_EXPORT");
    private static final AtomicInteger SEQ = new AtomicInteger();

    private Industry industry;
    private LeadSourceMaster source;
    private ActivityType call;
    private Employee admin;
    private Employee lakshmi;   // OWN scope
    private Employee ravi;      // OWN scope

    @BeforeEach
    void setUp() {
        int n = SEQ.incrementAndGet();
        industry = industryRepository.save(Industry.builder().name("FU Industry " + n).build());
        source = leadSourceRepository.save(LeadSourceMaster.builder().name("FU Source " + n).build());
        call = activityTypeRepository.save(ActivityType.builder().name("FU Call " + n).build());
        admin = employee("FU_ADMIN_" + n, Scope.ALL, "fu-admin" + n + "@t.test", "95000" + String.format("%05d", n * 3));
        lakshmi = employee("FU_EMP_L_" + n, Scope.OWN, "fu-lakshmi" + n + "@t.test", "95000" + String.format("%05d", n * 3 + 1));
        ravi = employee("FU_EMP_R_" + n, Scope.OWN, "fu-ravi" + n + "@t.test", "95000" + String.format("%05d", n * 3 + 2));
    }

    // ------------------------------------------------------------- fixtures

    private Employee employee(String roleName, Scope scope, String email, String phone) {
        Role role = roleRepository.save(Role.builder().name(roleName).rank(10).build());
        for (String code : CODES) {
            Permission p = permissionRepository.findAll().stream().filter(x -> code.equals(x.getCode())).findFirst()
                    .orElseGet(() -> permissionRepository.save(Permission.builder().code(code).build()));
            rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
        }
        return employeeRepository.save(Employee.builder().name(roleName.toLowerCase()).email(email).phone(phone)
                .password("x").role(role).isActive(true).build());
    }

    private Lead lead(String company, String contact, LeadStatus status, Employee owner) {
        return leadRepository.save(Lead.builder().companyName(company).contactPerson(contact)
                .phone("98" + String.format("%08d", SEQ.incrementAndGet() * 7)).city("Pune").state("MH")
                .industry(industry).leadSource(source).leadStatus(status)
                .leadValidity(status == LeadStatus.INVALID ? LeadValidity.INVALID : LeadValidity.VALID)
                .assignedEmployee(owner).build());
    }

    // A real conversion over HTTP (OpportunityService.convertLeadToOpportunity),
    // with the mandatory next action it creates as an opportunity follow-up.
    private long convert(Lead lead, String title, Employee as) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"productValue\":100,\"expectedClosingDate\":\""
                + BusinessClock.today().plusDays(30) + "\",\"salesStage\":\"IN PROGRESS\",\"leadValidity\":\"VALID\","
                + "\"nextActionDescription\":\"Send the proposal\",\"nextFollowUpDate\":\""
                + LocalDateTime.now().plusDays(3).withNano(0) + "\",\"nextActionActivityTypeId\":" + call.getId() + "}";
        String res = mockMvc.perform(post("/api/opportunities/convert/" + lead.getId()).header("Authorization", token(as))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private String followUpBody(Long leadId, Long opportunityId, Employee assignee, String remarks) {
        return "{" + (leadId != null ? "\"leadId\":" + leadId + "," : "")
                + (opportunityId != null ? "\"opportunityId\":" + opportunityId + "," : "")
                + "\"employeeId\":" + assignee.getId() + ",\"activityTypeId\":" + call.getId()
                + ",\"status\":\"PENDING\",\"scheduledDate\":\"" + LocalDateTime.now().plusDays(2).withNano(0)
                + "\",\"remarks\":\"" + remarks + "\"}";
    }

    private JsonNode create(Long leadId, Long opportunityId, Employee as) throws Exception {
        String res = mockMvc.perform(post("/api/followups").header("Authorization", token(as))
                        .contentType("application/json").content(followUpBody(leadId, opportunityId, as, "Call back")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private JsonNode getJson(String url, Employee as) throws Exception {
        return json.readTree(mockMvc.perform(get(url).header("Authorization", token(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String token(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void aFollowUpForAnUnconvertedLead_isLinkedToTheLead_andShowsItsCompany() throws Exception {

        Lead lead = lead("Sunrise Hospital", "Dr Rao", LeadStatus.NEW, lakshmi);

        JsonNode created = create(lead.getId(), null, lakshmi);
        JsonNode related = created.get("related");

        assertThat(related.get("kind").asText()).isEqualTo("LEAD");
        assertThat(related.get("leadId").asLong()).isEqualTo(lead.getId());
        assertThat(related.get("companyName").asText()).isEqualTo("Sunrise Hospital");
        assertThat(related.get("contactPerson").asText()).isEqualTo("Dr Rao");
        assertThat(related.get("leadViaOpportunity").asBoolean()).isFalse();
        assertThat(related.get("opportunityId").isNull()).isTrue();
    }

    @Test
    void anOpportunityFollowUp_hasNoLeadId_butShowsTheLeadAndCompanyThroughItsOpportunity() throws Exception {

        // the reported case: Opportunity "6kva online ups", Lead "-", assigned to lakshmi
        Lead lead = lead("Metro Diagnostics", "Ms Iyer", LeadStatus.VALID, lakshmi);
        long oppId = convert(lead, "6kva online ups", lakshmi);

        // the conversion's next action is an OPPORTUNITY follow-up: lead_id is NULL
        FollowUp nextAction = followUpRepository.findByOpportunityId(oppId).get(0);
        assertThat(nextAction.getLead()).isNull();

        JsonNode detail = getJson("/api/followups/" + nextAction.getId(), lakshmi);
        assertThat(detail.get("lead").isNull()).isTrue();                       // the stored relationship, untouched
        JsonNode related = detail.get("related");
        assertThat(related.get("kind").asText()).isEqualTo("OPPORTUNITY");
        assertThat(related.get("leadId").asLong()).isEqualTo(lead.getId());     // via opportunity.lead (a real FK)
        assertThat(related.get("leadViaOpportunity").asBoolean()).isTrue();
        assertThat(related.get("companyName").asText()).isEqualTo("Metro Diagnostics");
        assertThat(related.get("opportunityTitle").asText()).isEqualTo("6kva online ups");
        assertThat(related.get("opportunityStage").asText()).isEqualTo("IN PROGRESS");
        assertThat(related.get("ownerName").asText()).isEqualTo(lakshmi.getName());
        assertThat(related.get("label").asText()).isEqualTo("Metro Diagnostics - 6kva online ups");

        // the list carries the same block
        JsonNode page = getJson("/api/followups?size=200", lakshmi);
        JsonNode row = list(page.get("content")).stream()
                .filter(r -> r.get("id").asLong() == nextAction.getId()).findFirst().orElseThrow();
        assertThat(row.get("related").get("companyName").asText()).isEqualTo("Metro Diagnostics");

        // and the list can be searched by the opportunity name
        JsonNode found = getJson("/api/followups?size=200&search=6kva", lakshmi);
        assertThat(list(found.get("content"))).extracting(r -> r.get("id").asLong()).contains(nextAction.getId());
    }

    @Test
    void conversion_keepsTheLeadsEarlierFollowUpsOnTheLead_andNewOnesCanBeScheduledOnTheOpportunity() throws Exception {

        Lead lead = lead("Lotus Textiles", "Mr Shah", LeadStatus.VALID, lakshmi);
        long before = create(lead.getId(), null, lakshmi).get("id").asLong();

        long oppId = convert(lead, "10kva online ups", lakshmi);

        // the pre-conversion follow-up still belongs to the original lead
        FollowUp kept = followUpRepository.findById(before).orElseThrow();
        assertThat(kept.getLead().getId()).isEqualTo(lead.getId());
        assertThat(kept.getOpportunity()).isNull();

        // a new one for the converted lead goes on its opportunity
        JsonNode onOpportunity = create(null, oppId, lakshmi);
        assertThat(onOpportunity.get("related").get("leadId").asLong()).isEqualTo(lead.getId());
        assertThat(onOpportunity.get("related").get("companyName").asText()).isEqualTo("Lotus Textiles");

        // naming both is fine when they are the same record
        JsonNode both = create(lead.getId(), oppId, lakshmi);
        assertThat(both.get("related").get("kind").asText()).isEqualTo("LEAD_AND_OPPORTUNITY");
    }

    @Test
    void editing_keepsTheRelationships_andALeadWithAnotherCompanysOpportunity_isRefused() throws Exception {

        Lead a = lead("Alpha Pharma", "A", LeadStatus.VALID, lakshmi);
        Lead b = lead("Beta Steel", "B", LeadStatus.VALID, lakshmi);
        long oppA = convert(a, "UPS for Alpha", lakshmi);
        long oppB = convert(b, "UPS for Beta", lakshmi);

        long id = create(null, oppA, lakshmi).get("id").asLong();

        // an ordinary edit (remarks) that resubmits the same relationship
        String res = mockMvc.perform(put("/api/followups/" + id).header("Authorization", token(lakshmi))
                        .contentType("application/json").content(followUpBody(null, oppA, lakshmi, "Moved to Friday")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode edited = json.readTree(res);
        assertThat(edited.get("opportunity").get("id").asLong()).isEqualTo(oppA);
        assertThat(edited.get("related").get("companyName").asText()).isEqualTo("Alpha Pharma");
        assertThat(edited.get("remarks").asText()).isEqualTo("Moved to Friday");

        // lead Alpha + opportunity of Beta: two different companies -> refused, nothing changed
        mockMvc.perform(put("/api/followups/" + id).header("Authorization", token(lakshmi))
                        .contentType("application/json").content(followUpBody(a.getId(), oppB, lakshmi, "x")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/followups").header("Authorization", token(lakshmi))
                        .contentType("application/json").content(followUpBody(a.getId(), oppB, lakshmi, "x")))
                .andExpect(status().isBadRequest());

        FollowUp stored = followUpRepository.findById(id).orElseThrow();
        assertThat(stored.getOpportunity().getId()).isEqualTo(oppA);
        assertThat(stored.getLead()).isNull();
    }

    @Test
    void twoOpportunitiesWithTheSameName_areToldApartByCompanyAndId() throws Exception {

        Lead a = lead("Green Valley School", "Principal", LeadStatus.VALID, lakshmi);
        Lead b = lead("Harbour Logistics", "Ops head", LeadStatus.VALID, lakshmi);
        long oppA = convert(a, "6kva online ups", lakshmi);
        long oppB = convert(b, "6kva online ups", lakshmi);

        JsonNode targets = getJson("/api/followups/targets?type=OPPORTUNITY&search=6kva", lakshmi);
        List<JsonNode> sameName = list(targets).stream()
                .filter(t -> "6kva online ups".equals(t.get("opportunityTitle").asText())).toList();

        assertThat(sameName).extracting(t -> t.get("opportunityId").asLong()).contains(oppA, oppB);
        assertThat(sameName).extracting(t -> t.get("label").asText())
                .contains("Green Valley School - 6kva online ups", "Harbour Logistics - 6kva online ups");

        JsonNode fa = create(null, oppA, lakshmi);
        JsonNode fb = create(null, oppB, lakshmi);
        assertThat(fa.get("related").get("companyName").asText()).isEqualTo("Green Valley School");
        assertThat(fb.get("related").get("companyName").asText()).isEqualTo("Harbour Logistics");
    }

    @Test
    void anEmployeeCannotUseFollowUpsToReachAColleaguesLeadOrOpportunity() throws Exception {

        Lead ravisLead = lead("Ravi's Client", "X", LeadStatus.NEW, ravi);
        Lead ravisConverted = lead("Ravi's Deal Co", "Y", LeadStatus.VALID, ravi);
        long ravisOpp = convert(ravisConverted, "Ravi deal", ravi);
        long ravisFollowUp = create(ravisLead.getId(), null, ravi).get("id").asLong();

        mockMvc.perform(post("/api/followups").header("Authorization", token(lakshmi)).contentType("application/json")
                .content(followUpBody(ravisLead.getId(), null, lakshmi, "x"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/followups").header("Authorization", token(lakshmi)).contentType("application/json")
                .content(followUpBody(null, ravisOpp, lakshmi, "x"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/followups/" + ravisFollowUp).header("Authorization", token(lakshmi)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/followups/opportunity/" + ravisOpp).header("Authorization", token(lakshmi)))
                .andExpect(status().isForbidden());

        // and the picker never offers them
        JsonNode targets = getJson("/api/followups/targets?search=Ravi", lakshmi);
        assertThat(list(targets)).isEmpty();
        assertThat(list(getJson("/api/followups/targets?search=Ravi", admin))).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void thePicker_offersEveryEligibleRecord_andNothingIneligible() throws Exception {

        String tag = "Pick" + SEQ.incrementAndGet();
        Lead fresh = lead(tag + " New Co", "N", LeadStatus.NEW, lakshmi);
        Lead qualified = lead(tag + " Valid Co", "V", LeadStatus.VALID, lakshmi);
        Lead invalid = lead(tag + " Invalid Co", "I", LeadStatus.INVALID, lakshmi);
        Lead converted = lead(tag + " Converted Co", "C", LeadStatus.VALID, lakshmi);
        long oppId = convert(converted, tag + " deal", lakshmi);

        List<JsonNode> targets = list(getJson("/api/followups/targets?search=" + tag, lakshmi));

        assertThat(targets).filteredOn(t -> t.get("type").asText().equals("LEAD"))
                .extracting(t -> t.get("leadId").asLong())
                .containsExactlyInAnyOrder(fresh.getId(), qualified.getId());     // not invalid, not converted
        assertThat(targets).filteredOn(t -> t.get("type").asText().equals("OPPORTUNITY"))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.get("opportunityId").asLong()).isEqualTo(oppId);
                    assertThat(t.get("leadId").asLong()).isEqualTo(converted.getId());   // the converted lead, as its opportunity
                    assertThat(t.get("companyName").asText()).isEqualTo(tag + " Converted Co");
                    assertThat(t.get("stage").asText()).isEqualTo("IN PROGRESS");
                    assertThat(t.get("assignedEmployeeName").asText()).isEqualTo(lakshmi.getName());
                });
        assertThat(targets).extracting(t -> t.get("leadId").asLong()).doesNotContain(invalid.getId());

        // the picker is capped, and a role without follow-up permissions gets 403
        assertThat(list(getJson("/api/followups/targets?limit=1", lakshmi)).size()).isLessThanOrEqualTo(2);
    }

    @Test
    void aHistoricalFollowUpWithMissingLinks_doesNotBreak_andStillShowsWhatIsKnown() throws Exception {

        // data the application no longer creates: an opportunity without a lead
        Opportunity orphanOpp = opportunityRepository.save(Opportunity.builder().title("Legacy 5kva")
                .productValue(1.0).salesStage(salesStageRepository.findAll().get(0)).build());
        FollowUp legacy = followUpRepository.save(FollowUp.builder().opportunity(orphanOpp).employee(lakshmi)
                .activityType(call).status(FollowUpStatus.PENDING).scheduledDate(LocalDateTime.now().plusDays(1))
                .remarks("old").build());
        FollowUp noParent = followUpRepository.save(FollowUp.builder().employee(lakshmi)
                .activityType(call).status(FollowUpStatus.PENDING).scheduledDate(LocalDateTime.now().plusDays(1))
                .remarks("orphan").build());

        // no owner can be resolved -> only an ALL-scope role may open it; never a 500
        mockMvc.perform(get("/api/followups/" + legacy.getId()).header("Authorization", token(lakshmi)))
                .andExpect(status().isForbidden());

        JsonNode asAdmin = getJson("/api/followups/" + legacy.getId(), admin).get("related");
        assertThat(asAdmin.get("kind").asText()).isEqualTo("OPPORTUNITY");
        assertThat(asAdmin.get("companyName").isNull()).isTrue();             // not invented
        assertThat(asAdmin.get("opportunityTitle").asText()).isEqualTo("Legacy 5kva");
        assertThat(asAdmin.get("label").asText()).isEqualTo("Opportunity: Legacy 5kva");

        assertThat(getJson("/api/followups/" + noParent.getId(), admin).get("related").get("label").asText())
                .isEqualTo("No linked record");

        // the admin list and the export render them too
        assertThat(getJson("/api/followups?size=500", admin).get("content").size()).isPositive();
        mockMvc.perform(get("/api/followups/export?ids=" + legacy.getId() + "," + noParent.getId())
                .header("Authorization", token(admin))).andExpect(status().isOk());
    }
}
