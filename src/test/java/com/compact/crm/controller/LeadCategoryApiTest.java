package com.compact.crm.controller;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
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
import com.compact.crm.util.TableExport;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level (real security chain + controllers + H2) checks of the lead
 * lifecycle endpoints and export for ordinary employees: what the
 * operational list returns, that invalid/converted leads stay reachable via
 * the report endpoints, export file type, and 401/403/400 behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LeadCategoryApiTest {

    @Autowired private MockMvc mockMvc;
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

    private Employee admin;
    private Employee staff;
    private Employee otherStaff;
    private Employee plain;
    private Industry industry;
    private LeadSourceMaster source;
    private Lead invalidLead;

    @BeforeEach
    void setUp() {

        Role adminRole = role("ADMIN", 100);
        Role employeeRole = role("EMPLOYEE", 10);

        for (String code : List.of("LEAD_VIEW", "LEAD_MANAGE", "OPPORTUNITY_VIEW", "OPPORTUNITY_MANAGE",
                "ACTIVITY_VIEW", "FOLLOWUP_VIEW", "CUSTOMER_VIEW")) {
            grant(adminRole, code, Scope.ALL);
            grant(employeeRole, code, Scope.OWN);
        }

        // every role may export leads and opportunities (V11), at its view scope
        for (String code : List.of("LEAD_EXPORT", "OPPORTUNITY_EXPORT")) {
            grant(adminRole, code, Scope.ALL);
            grant(employeeRole, code, Scope.OWN);
        }

        // a role that was never granted export (the backend must still refuse it)
        Role noExportRole = role("NOEXPORT", 10);
        grant(noExportRole, "LEAD_VIEW", Scope.OWN);
        grant(noExportRole, "OPPORTUNITY_VIEW", Scope.OWN);

        admin = employee("Api Admin", "cat-admin@test.com", "9300000001", adminRole);
        staff = employee("Api Staff", "cat-mk@test.com", "9300000002", employeeRole);
        otherStaff = employee("Api Staff 2", "cat-mk2@test.com", "9300000003", employeeRole);
        plain = employee("Api Plain", "cat-pl@test.com", "9300000004", noExportRole);

        industry = industryRepository.save(Industry.builder().name("Cat Industry").build());
        source = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Cat Source").build());

        lead("Mk New", LeadStatus.NEW, staff, "00441234567890123456");
        lead("Mk Valid", LeadStatus.VALID, staff, "+919876543210");
        invalidLead = lead("Mk Invalid", LeadStatus.INVALID, staff, "9876500001");
        Lead converted = lead("Mk Converted", LeadStatus.VALID, staff, "9876500002");
        lead("Other New", LeadStatus.NEW, otherStaff, "9876500003");

        SalesStage stage = salesStageRepository.findAll().stream().findFirst()
                .orElseGet(() -> salesStageRepository.save(SalesStage.builder().name("IN PROGRESS").build()));
        opportunityRepository.save(Opportunity.builder()
                .title("Deal").lead(converted).salesStage(stage).leadValidity(LeadValidity.VALID)
                .productValue(1500000.0).expectedClosingDate(java.time.LocalDate.of(2027, 1, 15)).build());
    }

    // ---------- list: only active leads, scoped ----------

    @Test
    void leadsEndpoint_returnsOnlyActiveLeadsInTheCallersScope() throws Exception {

        mockMvc.perform(get("/api/leads").header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].companyName").value(
                        org.hamcrest.Matchers.containsInAnyOrder("Mk New", "Mk Valid")));
    }

    @Test
    void leadsEndpoint_noQueryParameterBringsBackInvalidOrConvertedLeads() throws Exception {

        for (String query : List.of("statusGroup=INVALID", "status=INVALID", "leadValidity=INVALID",
                "search=Converted", "category=ALL", "category=INVALID", "size=500")) {

            MvcResult result = mockMvc.perform(
                            get("/api/leads?" + query).header("Authorization", bearer(admin)))
                    .andExpect(status().isOk()).andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .as(query)
                    .doesNotContain("Mk Invalid")
                    .doesNotContain("Mk Converted");
        }
    }

    // ---------- reporting keeps every category reachable ----------

    @Test
    void reportEndpoint_returnsInvalidAndConvertedLeads() throws Exception {

        mockMvc.perform(get("/api/leads/report").header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));

        mockMvc.perform(get("/api/leads/report?category=INVALID").header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].companyName").value("Mk Invalid"))
                .andExpect(jsonPath("$.content[0].category").value("INVALID"));

        mockMvc.perform(get("/api/leads/report?category=CONVERTED").header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].companyName").value("Mk Converted"))
                .andExpect(jsonPath("$.content[0].category").value("CONVERTED"));
    }

    @Test
    void reportCounts_coverEveryCategory_andRespectScope() throws Exception {

        mockMvc.perform(get("/api/leads/report/counts").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.NEW").value(2))
                .andExpect(jsonPath("$.VALID_UNCONVERTED").value(1))
                .andExpect(jsonPath("$.INVALID").value(1))
                .andExpect(jsonPath("$.CONVERTED").value(1))
                .andExpect(jsonPath("$.TOTAL").value(5));

        mockMvc.perform(get("/api/leads/report/counts").header("Authorization", bearer(staff)))
                .andExpect(jsonPath("$.NEW").value(1))
                .andExpect(jsonPath("$.TOTAL").value(4));
    }

    @Test
    void invalidLeadDetailsAndInvalidPage_stayReachable() throws Exception {

        mockMvc.perform(get("/api/leads/" + invalidLead.getId()).header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("Mk Invalid"));

        mockMvc.perform(get("/api/leads/invalid").header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void reportEndpoint_unknownCategory_is400() throws Exception {

        mockMvc.perform(get("/api/leads/report?category=BOGUS").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    // ---------- export: file format, scope and permission ----------

    @Test
    void leadExport_isXlsxByDefault_withTextPhonesAndOnlyTheCallersActiveLeads() throws Exception {

        MvcResult result = mockMvc.perform(get("/api/leads/export").header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).isEqualTo(TableExport.XLSX_TYPE);
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("leads.xlsx");

        XSSFSheet sheet = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))
                .getSheetAt(0);

        List<String> companies = new ArrayList<>();
        List<String> phones = new ArrayList<>();
        int phoneCol = columnOf(sheet, "Phone");
        int companyCol = columnOf(sheet, "Company");

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            companies.add(sheet.getRow(r).getCell(companyCol).getStringCellValue());
            assertThat(sheet.getRow(r).getCell(phoneCol).getCellType()).isEqualTo(CellType.STRING);
            phones.add(sheet.getRow(r).getCell(phoneCol).getStringCellValue());
        }

        assertThat(companies).containsExactlyInAnyOrder("Mk New", "Mk Valid");
        assertThat(phones).containsExactlyInAnyOrder("00441234567890123456", "+919876543210");
    }

    @Test
    void leadExport_categoryAllAndInvalid_forStaff_staysInTheirOwnScope() throws Exception {

        byte[] all = mockMvc.perform(get("/api/leads/export?category=ALL").header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        // 4 data rows; "Other New" belongs to another marketer
        assertThat(new XSSFWorkbook(new ByteArrayInputStream(all)).getSheetAt(0).getLastRowNum()).isEqualTo(4);

        byte[] invalid = mockMvc.perform(get("/api/leads/export?category=INVALID").header("Authorization", bearer(staff)))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new XSSFWorkbook(new ByteArrayInputStream(invalid)).getSheetAt(0).getLastRowNum()).isEqualTo(1);
    }

    @Test
    void leadExport_csvIsOptIn_utf8WithBom() throws Exception {

        MvcResult result = mockMvc.perform(get("/api/leads/export?format=csv").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();

        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("leads.csv");
        assertThat(body[0]).isEqualTo((byte) 0xEF);
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("00441234567890123456");
    }

    @Test
    void exports_areForbiddenWithoutTheGrant_andUnauthenticatedIs401() throws Exception {

        for (String url : List.of("/api/leads/export", "/api/opportunities/export")) {

            mockMvc.perform(get(url).header("Authorization", bearer(plain)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        }

        // The customer export is unchanged (admin-only): an ordinary employee has no grant for it.
        mockMvc.perform(get("/api/customers/export").header("Authorization", bearer(staff)))
                .andExpect(status().isForbidden());
    }

    @Test
    void opportunityExport_staffGetsXlsxOfTheirOwnOpportunities_adminGetsAll() throws Exception {

        Lead others = lead("Other Valid", LeadStatus.VALID, otherStaff, "9876500009");
        opportunityRepository.save(Opportunity.builder()
                .title("Other Deal").lead(others).leadValidity(LeadValidity.VALID).productValue(10.0).build());

        MvcResult mine = mockMvc.perform(get("/api/opportunities/export").header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andReturn();

        assertThat(mine.getResponse().getContentType()).isEqualTo(TableExport.XLSX_TYPE);

        XSSFSheet sheet = new XSSFWorkbook(new ByteArrayInputStream(mine.getResponse().getContentAsByteArray()))
                .getSheetAt(0);

        assertThat(sheet.getLastRowNum()).isEqualTo(1);
        assertThat(sheet.getRow(1).getCell(columnOf(sheet, "Value")).getNumericCellValue()).isEqualTo(1500000.0);

        XSSFSheet adminSheet = new XSSFWorkbook(new ByteArrayInputStream(
                mockMvc.perform(get("/api/opportunities/export").header("Authorization", bearer(admin)))
                        .andReturn().getResponse().getContentAsByteArray())).getSheetAt(0);

        assertThat(adminSheet.getLastRowNum()).isEqualTo(2);
    }

    @Test
    void adminOnlyReportsStayAdminOnly_forStaff() throws Exception {

        mockMvc.perform(get("/api/reports/leads?from=2026-01-01&to=2026-12-31")
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private int columnOf(XSSFSheet sheet, String header) {
        for (var cell : sheet.getRow(0)) {
            if (header.equals(cell.getStringCellValue())) {
                return cell.getColumnIndex();
            }
        }
        throw new AssertionError("no column " + header);
    }

    private String bearer(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }

    private Role role(String name, int rank) {
        return roleRepository.findAll().stream().filter(r -> name.equals(r.getName())).findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name(name).rank(rank).build()));
    }

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissionRepository.findAll().stream().filter(x -> code.equals(x.getCode())).findFirst()
                .orElseGet(() -> permissionRepository.save(Permission.builder().code(code).build()));

        boolean granted = rolePermissionRepository.findAll().stream()
                .anyMatch(rp -> rp.getRole().getId().equals(role.getId())
                        && rp.getPermission().getId().equals(p.getId()));

        if (!granted) {
            rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
        }
    }

    private Employee employee(String name, String email, String phone, Role role) {
        return employeeRepository.save(Employee.builder()
                .name(name).email(email).phone(phone).password("x").role(role).build());
    }

    private Lead lead(String company, LeadStatus status, Employee owner, String phone) {
        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("C").phone(phone)
                .city("Pune").state("MH").industry(industry).leadSource(source)
                .leadStatus(status)
                .leadValidity(status == LeadStatus.INVALID ? LeadValidity.INVALID : LeadValidity.VALID)
                .assignedEmployee(owner).build());
    }
}
