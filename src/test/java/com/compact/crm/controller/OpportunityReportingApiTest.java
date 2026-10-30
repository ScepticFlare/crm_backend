package com.compact.crm.controller;

import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.OpportunityBattery;
import com.compact.crm.entity.OpportunityProduct;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.BatteryRepository;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.compact.crm.service.SalesStageService;
import com.compact.crm.util.BusinessClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end over HTTP (real security chain, JSON binding, controllers, H2):
 * mandatory Product/Battery on new Leads, Lead -> Opportunity conversion
 * carrying Product AND Battery (the missing-Battery regression), editing the
 * Opportunity's own contact / product / battery / closing date without
 * touching the Lead, exports whose columns follow the column chooser and
 * reflect those edits, the In Progress export matching its list, multi-value
 * filters (OR within a filter, AND across filters), "all matching minus
 * unticked" exports beyond one 100-row page, and RBAC scope on all of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpportunityReportingApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private EntityManager entityManager;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BatteryRepository batteryRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private SalesStageService salesStageService;

    private static final List<String> ALL_PERMISSIONS = List.of(
            "LEAD_VIEW", "LEAD_MANAGE", "LEAD_EXPORT",
            "OPPORTUNITY_VIEW", "OPPORTUNITY_MANAGE", "OPPORTUNITY_EXPORT",
            "CUSTOMER_VIEW", "CUSTOMER_EXPORT", "FOLLOWUP_VIEW", "FOLLOWUP_MANAGE", "ACTIVITY_VIEW");

    private Employee admin;
    private Employee staffA;
    private Employee staffB;
    private String adminToken;
    private String staffBToken;

    private Industry industry;
    private LeadSourceMaster leadSource;
    private Product productA;
    private Product productB;
    private Battery batteryA;
    private Battery batteryB;
    private ActivityType activityType;

    private SalesStage inProgress;
    private SalesStage won;

    @BeforeEach
    void setUp() {

        Role adminRole = role("ADMIN", 100);
        Role employeeRole = role("EMPLOYEE", 10);

        for (String code : ALL_PERMISSIONS) {
            grant(adminRole, code, Scope.ALL);
            grant(employeeRole, code, Scope.OWN);
        }

        admin = employee("Report Admin", "report-admin@test.com", "9300000001", adminRole);
        staffA = employee("Staff A", "staff-a@test.com", "9300000002", employeeRole);
        staffB = employee("Staff B", "staff-b@test.com", "9300000003", employeeRole);

        adminToken = "Bearer " + jwtService.generateToken(new UserPrincipal(admin));
        staffBToken = "Bearer " + jwtService.generateToken(new UserPrincipal(staffB));

        industry = industryRepository.save(Industry.builder().name("Report Industry").build());
        leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Report Source").build());
        productA = productRepository.save(Product.builder().name("Online UPS 10kVA").build());
        productB = productRepository.save(Product.builder().name("Inverter 5kVA").build());
        batteryA = batteryRepository.save(Battery.builder().name("SMF 12V 42Ah").build());
        batteryB = batteryRepository.save(Battery.builder().name("Lithium 48V").build());
        activityType = activityTypeRepository.save(ActivityType.builder().name("Report Call").build());

        inProgress = salesStageService.getByType(SalesStageType.IN_PROGRESS);
        won = salesStageService.getByType(SalesStageType.WON);
    }

    // ---------------------------------------------------------------- helpers

    private Role role(String name, int rank) {
        return roleRepository.findAll().stream()
                .filter(r -> name.equals(r.getName())).findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name(name).rank(rank).build()));
    }

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissionRepository.findAll().stream()
                .filter(x -> code.equals(x.getCode())).findFirst()
                .orElseGet(() -> permissionRepository.save(Permission.builder().code(code).build()));

        rolePermissionRepository.findAll().stream()
                .filter(rp -> rp.getRole().getId().equals(role.getId()) && rp.getPermission().getId().equals(p.getId()))
                .findFirst()
                .ifPresentOrElse(
                        rp -> {
                            rp.setScope(scope);
                            rolePermissionRepository.save(rp);
                        },
                        () -> rolePermissionRepository.save(
                                RolePermission.builder().role(role).permission(p).scope(scope).build()));
    }

    private Employee employee(String name, String email, String phone, Role role) {
        return employeeRepository.save(Employee.builder()
                .name(name).email(email).phone(phone).password("x").role(role).build());
    }

    // A VALID lead (ready to convert) with the given line items and a
    // qualifying next action, stored directly.
    private Lead storedLead(String company, Employee owner, Product product, Battery battery) {

        Lead lead = Lead.builder()
                .companyName(company).contactPerson("Ravi Kumar").designation("Manager")
                .phone("9812345678").email("ravi@" + company.toLowerCase().replace(" ", "") + ".test")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.VALID)
                .assignedEmployee(owner).build();

        if (product != null) {
            lead.getLeadProducts().add(LeadProduct.builder().lead(lead).product(product).quantity(2).build());
        }

        if (battery != null) {
            lead.getLeadBatteries().add(LeadBattery.builder().lead(lead).battery(battery).quantity(4).build());
        }

        lead = leadRepository.save(lead);

        followUpRepository.save(FollowUp.builder()
                .lead(lead).employee(owner).activityType(activityType)
                .status(FollowUpStatus.PENDING)
                .scheduledDate(java.time.LocalDateTime.now().plusDays(7))
                .remarks("Call to discuss the quotation").build());

        return lead;
    }

    // An Opportunity stored directly (bulk data for the list/export tests).
    private Opportunity storedOpportunity(String title, Employee owner, SalesStage stage, Product product, Battery battery) {

        Lead lead = leadRepository.save(Lead.builder()
                .companyName(title + " Co").contactPerson("C").phone("9000000000")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.VALID)
                .assignedEmployee(owner).build());

        Opportunity o = Opportunity.builder()
                .title(title).lead(lead).salesStage(stage).leadValidity(LeadValidity.VALID)
                .expectedClosingDate(BusinessClock.today().plusDays(30))
                .contactPerson("C").contactPhone("9000000000").build();

        o.getOpportunityProducts().add(OpportunityProduct.builder().opportunity(o).product(product).quantity(1).build());
        o.getOpportunityBatteries().add(OpportunityBattery.builder().opportunity(o).battery(battery).quantity(1).build());

        return opportunityRepository.save(o);
    }

    private Map<String, Object> leadBody(String company, Long productId, Long batteryId) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", company);
        body.put("contactPerson", "Meera");
        body.put("designation", "Buyer");
        body.put("phone", "9876501234");
        body.put("city", "Pune");
        body.put("state", "MH");
        body.put("industryId", industry.getId());
        body.put("leadSourceId", leadSource.getId());
        body.put("leadStatus", "NEW");
        body.put("assignedEmployeeId", admin.getId());

        if (productId != null) {
            body.put("products", List.of(Map.of("productId", productId, "quantity", 1)));
        }

        if (batteryId != null) {
            body.put("batteries", List.of(Map.of("batteryId", batteryId, "quantity", 1)));
        }

        return body;
    }

    private Map<String, Object> opportunityBody(String stage, String closingDate) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "UPS for plant");
        body.put("productValue", 250000);
        body.put("expectedClosingDate", closingDate);
        body.put("salesStage", stage);
        body.put("leadValidity", "VALID");
        return body;
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token, int expectedStatus) throws Exception {
        return mockMvc.perform(request.header("Authorization", token))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    // CSV export -> rows (header first), BOM stripped.
    private List<List<String>> csv(MvcResult result) {

        String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)
                .replace("﻿", "");

        List<List<String>> rows = new ArrayList<>();

        for (String line : text.split("\\r?\\n")) {
            if (!line.isEmpty()) {
                rows.add(Arrays.stream(line.split(",", -1))
                        .map(c -> c.startsWith("\"") && c.endsWith("\"") ? c.substring(1, c.length() - 1) : c)
                        .toList());
            }
        }

        return rows;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ---------------------------------------------------------- Lead create

    @Test
    void creatingALead_requiresAProductAndABattery_butAValidLeadIsStillCreated() throws Exception {

        mockMvc.perform(post("/api/leads").header("Authorization", adminToken).contentType("application/json")
                        .content(json(leadBody("No Product Ltd", null, batteryA.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please select at least one Product."));

        mockMvc.perform(post("/api/leads").header("Authorization", adminToken).contentType("application/json")
                        .content(json(leadBody("No Battery Ltd", productA.getId(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please select at least one Battery."));

        // an unfilled row does not count as a product
        Map<String, Object> blankRow = leadBody("Blank Row Ltd", null, batteryA.getId());
        blankRow.put("products", List.of());
        mockMvc.perform(post("/api/leads").header("Authorization", adminToken).contentType("application/json")
                        .content(json(blankRow)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please select at least one Product."));

        assertThat(leadRepository.findAll()).noneMatch(l -> l.getCompanyName().endsWith(" Ltd"));

        mockMvc.perform(post("/api/leads").header("Authorization", adminToken).contentType("application/json")
                        .content(json(leadBody("Complete Ltd", productA.getId(), batteryA.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadProducts[0].product.name").value("Online UPS 10kVA"))
                .andExpect(jsonPath("$.leadBatteries[0].battery.name").value("SMF 12V 42Ah"));
    }

    @Test
    void anOlderLeadWithoutProductOrBattery_canStillBeEdited_butALeadCannotLoseThem() throws Exception {

        // historical lead: no product, no battery
        Lead legacy = leadRepository.save(Lead.builder()
                .companyName("Legacy Co").contactPerson("Old").designation("M").phone("9811111111")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.NEW).leadValidity(LeadValidity.VALID).assignedEmployee(admin).build());

        Map<String, Object> edit = leadBody("Legacy Co", null, null);
        edit.put("city", "Mumbai");

        mockMvc.perform(put("/api/leads/" + legacy.getId()).header("Authorization", adminToken)
                        .contentType("application/json").content(json(edit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Mumbai"));

        // a lead that has them cannot drop them in an edit
        MvcResult created = perform(post("/api/leads").contentType("application/json")
                .content(json(leadBody("Keeps Items", productA.getId(), batteryA.getId()))), adminToken, 200);
        long id = read(created).get("id").asLong();

        mockMvc.perform(put("/api/leads/" + id).header("Authorization", adminToken)
                        .contentType("application/json").content(json(leadBody("Keeps Items", productA.getId(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please select at least one Battery."));
    }

    // ------------------------------------------ conversion and Opportunity edit

    @Test
    void conversion_carriesProductAndBattery_andTheOpportunityIsThenEditedOnItsOwn() throws Exception {

        String closing = BusinessClock.today().plusDays(30).toString();
        Lead lead = storedLead("Sunrise Foods", admin, productA, batteryA);

        // Convert: Product AND Battery (the regression) plus contact are copied.
        MvcResult converted = mockMvc.perform(post("/api/opportunities/convert/" + lead.getId())
                        .header("Authorization", adminToken).contentType("application/json")
                        .content(json(opportunityBody("IN PROGRESS", closing))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opportunityProducts[0].product.name").value("Online UPS 10kVA"))
                .andExpect(jsonPath("$.opportunityProducts[0].quantity").value(2))
                .andExpect(jsonPath("$.opportunityBatteries[0].battery.name").value("SMF 12V 42Ah"))
                .andExpect(jsonPath("$.opportunityBatteries[0].quantity").value(4))
                .andExpect(jsonPath("$.contactPerson").value("Ravi Kumar"))
                .andExpect(jsonPath("$.contactPhone").value("9812345678"))
                .andExpect(jsonPath("$.contactEmail").value("ravi@sunrisefoods.test"))
                .andReturn();

        long id = read(converted).get("id").asLong();

        // Persisted, not just echoed back.
        flushAndClear();
        Opportunity stored = opportunityRepository.findById(id).orElseThrow();
        assertThat(stored.getOpportunityBatteries()).extracting(b -> b.getBattery().getName()).containsExactly("SMF 12V 42Ah");
        assertThat(stored.getOpportunityProducts()).extracting(p -> p.getProduct().getName()).containsExactly("Online UPS 10kVA");

        mockMvc.perform(get("/api/opportunities/" + id).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opportunityBatteries[0].battery.name").value("SMF 12V 42Ah"));

        // The requirement changes: new contact, Product B x3, Battery B x1, later closing date.
        String newClosing = BusinessClock.today().plusDays(60).toString();
        Map<String, Object> edit = opportunityBody("IN PROGRESS", newClosing);
        edit.put("closingDateChangeReason", "Customer moved budget approval");
        edit.put("contactPerson", "Anita Desai");
        edit.put("contactPhone", "9000011111");
        edit.put("contactEmail", "anita@sunrisefoods.test");
        edit.put("products", List.of(Map.of("productId", productB.getId(), "quantity", 3)));
        edit.put("batteries", List.of(Map.of("batteryId", batteryB.getId(), "quantity", 1)));

        mockMvc.perform(put("/api/opportunities/" + id).header("Authorization", adminToken)
                        .contentType("application/json").content(json(edit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactPerson").value("Anita Desai"))
                .andExpect(jsonPath("$.opportunityProducts.length()").value(1))
                .andExpect(jsonPath("$.opportunityProducts[0].product.name").value("Inverter 5kVA"))
                .andExpect(jsonPath("$.opportunityBatteries[0].battery.name").value("Lithium 48V"));

        // Re-read from the database.
        flushAndClear();
        mockMvc.perform(get("/api/opportunities/" + id).header("Authorization", adminToken))
                .andExpect(jsonPath("$.contactPerson").value("Anita Desai"))
                .andExpect(jsonPath("$.contactPhone").value("9000011111"))
                .andExpect(jsonPath("$.contactEmail").value("anita@sunrisefoods.test"))
                .andExpect(jsonPath("$.expectedClosingDate").value(newClosing))
                .andExpect(jsonPath("$.opportunityProducts[0].product.name").value("Inverter 5kVA"))
                .andExpect(jsonPath("$.opportunityProducts[0].quantity").value(3))
                .andExpect(jsonPath("$.opportunityBatteries[0].battery.name").value("Lithium 48V"));

        // The Lead keeps its original values (traceability) and stays linked.
        Lead reloadedLead = leadRepository.findById(lead.getId()).orElseThrow();
        assertThat(reloadedLead.getContactPerson()).isEqualTo("Ravi Kumar");
        assertThat(reloadedLead.getLeadProducts()).extracting(p -> p.getProduct().getName()).containsExactly("Online UPS 10kVA");
        assertThat(reloadedLead.getLeadBatteries()).extracting(b -> b.getBattery().getName()).containsExactly("SMF 12V 42Ah");
        assertThat(opportunityRepository.findById(id).orElseThrow().getLead().getId()).isEqualTo(lead.getId());

        // A later save that does not send the details (older client / other
        // fields only) does NOT reset them to the Lead's values.
        Map<String, Object> titleOnly = opportunityBody("IN PROGRESS", newClosing);
        titleOnly.put("title", "UPS for plant - phase 2");
        mockMvc.perform(put("/api/opportunities/" + id).header("Authorization", adminToken)
                        .contentType("application/json").content(json(titleOnly)))
                .andExpect(status().isOk());

        // Editing the Lead afterwards does not change the Opportunity either.
        Map<String, Object> leadEdit = leadBody("Sunrise Foods", productA.getId(), batteryA.getId());
        leadEdit.put("leadStatus", "VALID");
        leadEdit.put("contactPerson", "Someone Else");
        mockMvc.perform(put("/api/leads/" + lead.getId()).header("Authorization", adminToken)
                        .contentType("application/json").content(json(leadEdit)))
                .andExpect(status().isOk());

        flushAndClear();
        mockMvc.perform(get("/api/opportunities/" + id).header("Authorization", adminToken))
                .andExpect(jsonPath("$.title").value("UPS for plant - phase 2"))
                .andExpect(jsonPath("$.contactPerson").value("Anita Desai"))
                .andExpect(jsonPath("$.opportunityProducts[0].product.name").value("Inverter 5kVA"))
                .andExpect(jsonPath("$.opportunityBatteries[0].battery.name").value("Lithium 48V"));

        // The report: chosen columns, in order, with the CURRENT values.
        MvcResult export = perform(get("/api/opportunities/export")
                .param("activeOnly", "true").param("format", "csv")
                .param("columns", "company,contactPerson,contactPhone,contactEmail,products,batteries,date,stage,assignedEmployee"),
                adminToken, 200);

        List<List<String>> rows = csv(export);
        assertThat(rows.get(0)).containsExactly("Company", "Contact Person", "Contact Phone", "Contact Email",
                "Products", "Batteries", "Expected Closing Date", "Stage", "Assigned Employee");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("Sunrise Foods", "Anita Desai", "9000011111", "anita@sunrisefoods.test",
                "Inverter 5kVA x3", "Lithium 48V x1", newClosing, "IN PROGRESS", "Report Admin");
    }

    @Test
    void opportunityDetailEdits_areValidated() throws Exception {

        String closing = BusinessClock.today().plusDays(30).toString();
        Lead lead = storedLead("Validate Co", admin, productA, batteryA);

        long id = read(perform(post("/api/opportunities/convert/" + lead.getId()).contentType("application/json")
                .content(json(opportunityBody("IN PROGRESS", closing))), adminToken, 200)).get("id").asLong();

        Map<String, Object> badPhone = opportunityBody("IN PROGRESS", closing);
        badPhone.put("contactPhone", "12345");
        mockMvc.perform(put("/api/opportunities/" + id).header("Authorization", adminToken)
                        .contentType("application/json").content(json(badPhone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Contact Phone must contain exactly 10 digits."));

        Map<String, Object> noBattery = opportunityBody("IN PROGRESS", closing);
        noBattery.put("batteries", List.of());
        mockMvc.perform(put("/api/opportunities/" + id).header("Authorization", adminToken)
                        .contentType("application/json").content(json(noBattery)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please keep at least one Battery on the opportunity."));

        Map<String, Object> clearedPerson = opportunityBody("IN PROGRESS", closing);
        clearedPerson.put("contactPerson", "  ");
        mockMvc.perform(put("/api/opportunities/" + id).header("Authorization", adminToken)
                        .contentType("application/json").content(json(clearedPerson)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Contact Person is required."));

        // nothing was changed by the rejected edits
        flushAndClear();
        Opportunity stored = opportunityRepository.findById(id).orElseThrow();
        assertThat(stored.getContactPhone()).isEqualTo("9812345678");
        assertThat(stored.getOpportunityBatteries()).hasSize(1);
    }

    @Test
    void anEmployeeCannotEditAnotherEmployeesOpportunity() throws Exception {

        Opportunity ofA = storedOpportunity("A's deal", staffA, inProgress, productA, batteryA);

        Map<String, Object> edit = opportunityBody("IN PROGRESS", BusinessClock.today().plusDays(30).toString());
        edit.put("contactPerson", "Hijacked");

        mockMvc.perform(put("/api/opportunities/" + ofA.getId()).header("Authorization", staffBToken)
                        .contentType("application/json").content(json(edit)))
                .andExpect(status().isForbidden());

        flushAndClear();
        assertThat(opportunityRepository.findById(ofA.getId()).orElseThrow().getContactPerson()).isEqualTo("C");
    }

    // ------------------------------------ lists, filters, selection, export

    @Test
    void inProgressExport_matchesTheInProgressList_andAllMatchingSelectionCoversMoreThanOnePage() throws Exception {

        List<Opportunity> aInProgress = new ArrayList<>();

        for (int i = 1; i <= 105; i++) {
            aInProgress.add(storedOpportunity(String.format("A-%03d", i), staffA, inProgress, productA, batteryA));
        }

        for (int i = 1; i <= 3; i++) {
            storedOpportunity("A-WON-" + i, staffA, won, productA, batteryA);
        }

        for (int i = 1; i <= 5; i++) {
            storedOpportunity("B-" + i, staffB, inProgress, productB, batteryB);
        }

        flushAndClear();

        // The list: 105 for staff A, 100 per page.
        mockMvc.perform(get("/api/opportunities/in-progress").header("Authorization", adminToken)
                        .param("assignedEmployeeId", staffA.getId().toString()).param("size", "100"))
                .andExpect(jsonPath("$.totalElements").value(105))
                .andExpect(jsonPath("$.content.length()").value(100));

        // "Select all 105 matching" -> export: every one, no duplicates, no WON.
        MvcResult all = mockMvc.perform(get("/api/opportunities/export").header("Authorization", adminToken)
                        .param("activeOnly", "true").param("assignedEmployeeId", staffA.getId().toString())
                        .param("columns", "title,stage").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Row-Count", "105"))
                .andReturn();

        List<List<String>> rows = csv(all);
        Set<String> titles = new HashSet<>();
        rows.subList(1, rows.size()).forEach(r -> titles.add(r.get(0)));
        assertThat(rows).hasSize(106);
        assertThat(titles).hasSize(105).allMatch(t -> t.startsWith("A-") && !t.startsWith("A-WON"));

        // ...minus two unticked rows (one of them from page 2).
        Long first = aInProgress.get(0).getId();
        Long onPageTwo = aInProgress.get(102).getId();

        MvcResult minus = mockMvc.perform(get("/api/opportunities/export").header("Authorization", adminToken)
                        .param("activeOnly", "true").param("assignedEmployeeId", staffA.getId().toString())
                        .param("excludeIds", first + "," + onPageTwo)
                        .param("columns", "title").param("format", "csv"))
                .andExpect(header().string("X-Export-Row-Count", "103"))
                .andReturn();

        List<String> remaining = csv(minus).stream().skip(1).map(r -> r.get(0)).toList();
        assertThat(remaining).hasSize(103).doesNotContain("A-001", "A-103");

        // The line items / next follow-up for 105 rows are loaded in a few
        // batched queries, not one (or two) per row.
        flushAndClear();
        org.hibernate.stat.Statistics stats = entityManager.getEntityManagerFactory()
                .unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        perform(get("/api/opportunities/export")
                .param("activeOnly", "true").param("assignedEmployeeId", staffA.getId().toString())
                .param("columns", "company,title,products,batteries,assignedEmployee,industry,nextFollowUp")
                .param("format", "csv"), adminToken, 200);

        long statements = stats.getPrepareStatementCount();
        stats.setStatisticsEnabled(false);
        assertThat(statements).isBetween(1L, 29L);

        // Without the active-stage restriction (the old In Progress export bug)
        // the WON ones would have been included.
        mockMvc.perform(get("/api/opportunities/export").header("Authorization", adminToken)
                        .param("assignedEmployeeId", staffA.getId().toString()).param("format", "csv"))
                .andExpect(header().string("X-Export-Row-Count", "108"));
    }

    @Test
    void multiValueFilters_areOrWithinAFilter_andAndAcrossFilters_inTheListAndTheExport() throws Exception {

        storedOpportunity("A-UPS-1", staffA, inProgress, productA, batteryA);
        storedOpportunity("A-UPS-2", staffA, won, productA, batteryB);
        storedOpportunity("A-INV-1", staffA, inProgress, productB, batteryB);
        storedOpportunity("B-UPS-1", staffB, inProgress, productA, batteryA);
        storedOpportunity("B-INV-1", staffB, inProgress, productB, batteryA);

        flushAndClear();

        // Single value - unchanged behavior.
        mockMvc.perform(get("/api/opportunities").header("Authorization", adminToken)
                        .param("productId", productA.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(3));

        // Several products: OR.
        mockMvc.perform(get("/api/opportunities").header("Authorization", adminToken)
                        .param("productId", productA.getId().toString(), productB.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(5));

        // Several stages: OR.
        mockMvc.perform(get("/api/opportunities").header("Authorization", adminToken)
                        .param("salesStageId", inProgress.getId().toString(), won.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(5));

        // Several employees AND several stages AND one battery.
        mockMvc.perform(get("/api/opportunities").header("Authorization", adminToken)
                        .param("assignedEmployeeId", staffA.getId().toString(), staffB.getId().toString())
                        .param("salesStageId", inProgress.getId().toString(), won.getId().toString())
                        .param("batteryId", batteryB.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2));

        // Employee A AND product B -> only A-INV-1.
        mockMvc.perform(get("/api/opportunities").header("Authorization", adminToken)
                        .param("assignedEmployeeId", staffA.getId().toString())
                        .param("productId", productB.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("A-INV-1"));

        // An empty value is "no restriction", not "match nothing".
        mockMvc.perform(get("/api/opportunities").header("Authorization", adminToken).param("productId", ""))
                .andExpect(jsonPath("$.totalElements").value(5));

        // The export applies exactly the same filters.
        MvcResult export = perform(get("/api/opportunities/export")
                .param("assignedEmployeeId", staffA.getId().toString(), staffB.getId().toString())
                .param("salesStageId", inProgress.getId().toString(), won.getId().toString())
                .param("batteryId", batteryB.getId().toString())
                .param("columns", "title").param("format", "csv"), adminToken, 200);

        assertThat(csv(export).stream().skip(1).map(r -> r.get(0)).toList())
                .containsExactlyInAnyOrder("A-UPS-2", "A-INV-1");
    }

    @Test
    void exportsNeverLeaveTheCallersScope_whateverIdsOrFiltersAreSent() throws Exception {

        Opportunity ofA = storedOpportunity("A-only", staffA, inProgress, productA, batteryA);
        storedOpportunity("B-1", staffB, inProgress, productA, batteryA);
        storedOpportunity("B-2", staffB, inProgress, productA, batteryA);

        flushAndClear();

        // B lists only their own...
        mockMvc.perform(get("/api/opportunities").header("Authorization", staffBToken))
                .andExpect(jsonPath("$.totalElements").value(2));

        // ...exports all matching -> only their own...
        MvcResult all = perform(get("/api/opportunities/export")
                .param("columns", "title").param("format", "csv"), staffBToken, 200);
        assertThat(csv(all).stream().skip(1).map(r -> r.get(0)).toList()).containsExactlyInAnyOrder("B-1", "B-2");

        // ...and cannot widen it with A's id or A as the assignee filter.
        MvcResult byIds = perform(get("/api/opportunities/export")
                .param("ids", ofA.getId().toString()).param("format", "csv"), staffBToken, 200);
        assertThat(csv(byIds)).hasSize(1);

        MvcResult byFilter = perform(get("/api/opportunities/export")
                .param("assignedEmployeeId", staffA.getId().toString()).param("format", "csv"), staffBToken, 200);
        assertThat(csv(byFilter)).hasSize(1);
    }

    @Test
    void leadFilters_acceptSeveralStatusesAndProducts() throws Exception {

        leadRepository.save(Lead.builder().companyName("F-New").contactPerson("x").phone("9000000001")
                .industry(industry).leadSource(leadSource).leadStatus(LeadStatus.NEW)
                .leadValidity(LeadValidity.VALID).assignedEmployee(admin).build());
        leadRepository.save(Lead.builder().companyName("F-Valid").contactPerson("x").phone("9000000002")
                .industry(industry).leadSource(leadSource).leadStatus(LeadStatus.VALID)
                .leadValidity(LeadValidity.VALID).assignedEmployee(admin).build());
        leadRepository.save(Lead.builder().companyName("F-Invalid").contactPerson("x").phone("9000000003")
                .industry(industry).leadSource(leadSource).leadStatus(LeadStatus.INVALID).invalidReason("spam")
                .leadValidity(LeadValidity.INVALID).assignedEmployee(admin).build());

        storedLead("F-UPS", admin, productA, batteryA);
        storedLead("F-INV", admin, productB, batteryB);

        flushAndClear();

        mockMvc.perform(get("/api/leads/report").header("Authorization", adminToken)
                        .param("search", "F-").param("statusGroup", "NEW", "INVALID"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/leads/report").header("Authorization", adminToken)
                        .param("productId", productA.getId().toString(), productB.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/leads/report").header("Authorization", adminToken)
                        .param("productId", productA.getId().toString(), productB.getId().toString())
                        .param("batteryId", batteryB.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].companyName").value("F-INV"));
    }

    @Test
    void customerExport_offersTheWonOpportunitysProductsAndBatteries_andFollowsTheChosenColumns() throws Exception {

        Opportunity wonOpp = storedOpportunity("Won deal", admin, won, productB, batteryB);

        customerRepository.save(Customer.builder()
                .customerCode("CUST9001").companyName("Won deal Co").contactPerson("Asha")
                .phone("9812300000").email("asha@won.test").gstNumber("27ABCDE1234F1Z5")
                .assignedEmployee(admin).opportunity(wonOpp).build());

        flushAndClear();

        MvcResult export = perform(get("/api/customers/export")
                .param("productId", productB.getId().toString())
                .param("columns", "customerCode,companyName,products,batteries,gstNumber")
                .param("format", "csv"), adminToken, 200);

        List<List<String>> rows = csv(export);
        assertThat(rows.get(0)).containsExactly("Customer Code", "Company", "Products", "Batteries", "GST Number");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("CUST9001", "Won deal Co", "Inverter 5kVA x1", "Lithium 48V x1",
                "27ABCDE1234F1Z5");

        // The list response carries the same values for the table columns.
        mockMvc.perform(get("/api/customers").header("Authorization", adminToken)
                        .param("batteryId", batteryB.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunity.opportunityBatteries[0].battery.name").value("Lithium 48V"));

        // A product the customer does not have filters it out.
        mockMvc.perform(get("/api/customers").header("Authorization", adminToken)
                        .param("productId", productA.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anOlderClientThatSendsNoColumns_stillGetsTheDefaultColumnSet() throws Exception {

        storedOpportunity("Default cols", admin, inProgress, productA, batteryA);
        flushAndClear();

        MvcResult export = perform(get("/api/opportunities/export").param("format", "csv"), adminToken, 200);

        assertThat(csv(export).get(0)).contains("Company", "Opportunity Title", "Products", "Batteries",
                "Expected Closing Date", "Stage", "Assigned Employee");
    }

    @Test
    void theNextFollowUpDateIsReportedForAnOpportunity() throws Exception {

        String closing = BusinessClock.today().plusDays(30).toString();
        Lead lead = storedLead("Next FU Co", admin, productA, batteryA);

        long id = read(perform(post("/api/opportunities/convert/" + lead.getId()).contentType("application/json")
                .content(json(opportunityBody("IN PROGRESS", closing))), adminToken, 200)).get("id").asLong();

        mockMvc.perform(get("/api/opportunities").header("Authorization", adminToken).param("search", "Next FU Co"))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].nextFollowUpDate").isNotEmpty());

        MvcResult export = perform(get("/api/opportunities/export").param("search", "Next FU Co")
                .param("columns", "title,nextFollowUp").param("format", "csv"), adminToken, 200);

        assertThat(csv(export).get(1).get(1)).isNotBlank();
    }
}
