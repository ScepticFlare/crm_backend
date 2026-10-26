package com.compact.crm.service;

import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.search.LeadSearchCriteria;
import com.compact.crm.dto.search.OpportunitySearchCriteria;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.LeadCategory;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.compact.crm.security.AccessControlService.LEAD_EXPORT;
import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_EXPORT;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lead lifecycle categories on a real H2 database with the real services:
 * what the operational Leads page lists, that invalid / converted leads stay
 * reportable, category counts, and export authorization / data scope for the
 * MARKETING role.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, FollowUpService.class, CustomerService.class,
        SalesStageService.class, ActivityLogService.class, AccessControlService.class,
        CurrentUserService.class, DashboardService.class})
class LeadCategoryIntegrationTest {

    @Autowired private LeadService leadService;
    @Autowired private OpportunityService opportunityService;
    @Autowired private SalesStageService salesStageService;
    @Autowired private DashboardService dashboardService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;

    private Employee admin;
    private Employee manager;
    private Employee employee;
    private Employee marketing;
    private Employee otherMarketing;
    private Industry industry;
    private LeadSourceMaster source;
    private ActivityType call;

    private final Map<String, Permission> permissions = new HashMap<>();

    // One lead of every category, all owned by `marketing` unless noted.
    private Lead newLead;
    private Lead validLead;
    private Lead invalidLead;
    private Lead convertedLead;
    private Lead legacyLead;       // stored WON, no opportunity -> NEW
    private Lead nullStatusLead;   // no stored status           -> NEW
    private Lead othersNewLead;    // owned by otherMarketing

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());
        Role marketingRole = roleRepository.save(Role.builder().name("MARKETING").rank(10).build());

        for (String code : List.of(LEAD_MANAGE, LEAD_VIEW, OPPORTUNITY_MANAGE, OPPORTUNITY_VIEW,
                AccessControlService.FOLLOWUP_VIEW, AccessControlService.FOLLOWUP_MANAGE,
                AccessControlService.CUSTOMER_VIEW)) {
            grant(adminRole, code, Scope.ALL);
            grant(managerRole, code, Scope.TEAM);
            grant(employeeRole, code, Scope.OWN);
            grant(marketingRole, code, Scope.OWN);
        }

        // Export: ADMIN (ALL) and MARKETING (OWN) only - exactly the V5 + V11 shape.
        grant(adminRole, LEAD_EXPORT, Scope.ALL);
        grant(adminRole, OPPORTUNITY_EXPORT, Scope.ALL);
        grant(marketingRole, LEAD_EXPORT, Scope.OWN);
        grant(marketingRole, OPPORTUNITY_EXPORT, Scope.OWN);

        admin = employee("Admin", "a@t.com", "9500000001", adminRole, null);
        manager = employee("Manager", "m@t.com", "9500000002", managerRole, null);
        employee = employee("Employee", "e@t.com", "9500000003", employeeRole, manager);
        marketing = employee("Marketing", "mk@t.com", "9500000004", marketingRole, null);
        otherMarketing = employee("Marketing2", "mk2@t.com", "9500000005", marketingRole, null);

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        source = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());
        call = activityTypeRepository.save(ActivityType.builder().name("Call").build());

        salesStageService.ensureCoreStages();

        authenticateAs(admin);

        newLead = leadService.createLead(request("New Co", LeadStatus.NEW, marketing));
        validLead = leadService.createLead(request("Valid Co", LeadStatus.VALID, marketing));

        LeadRequest invalid = request("Invalid Co", LeadStatus.INVALID, marketing);
        invalid.setInvalidReason("Wrong number");
        invalidLead = leadService.createLead(invalid);

        convertedLead = leadService.createLead(request("Converted Co", LeadStatus.VALID, marketing));
        opportunityService.convertLeadToOpportunity(convertedLead.getId(), oppRequest());

        legacyLead = stored("Legacy Co", LeadStatus.WON, marketing);
        nullStatusLead = stored("Null Co", null, marketing);

        othersNewLead = leadService.createLead(request("Others Co", LeadStatus.NEW, otherMarketing));
    }

    // ===================================================== the operational Leads page

    @Test
    void leadsPage_listsOnlyNewAndValidUnconverted() {

        Page<Lead> page = leadService.searchActiveLeads(new LeadSearchCriteria(), 0, 50, null, null);

        assertThat(names(page)).containsExactlyInAnyOrder(
                "New Co", "Valid Co", "Legacy Co", "Null Co", "Others Co");

        assertThat(page.getTotalElements()).isEqualTo(5);
    }

    @Test
    void leadsPage_paginationTotalsAndPagesExcludeHiddenLeads() {

        Page<Lead> first = leadService.searchActiveLeads(new LeadSearchCriteria(), 0, 2, null, null);
        Page<Lead> last = leadService.searchActiveLeads(new LeadSearchCriteria(), 2, 2, null, null);

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.getContent()).hasSize(2);
        assertThat(last.getContent()).hasSize(1);
    }

    @Test
    void leadsPage_noFilterCanLeakInvalidOrConvertedLeads() {

        LeadSearchCriteria byInvalidGroup = new LeadSearchCriteria();
        byInvalidGroup.setStatusGroup(LeadStatus.INVALID);

        LeadSearchCriteria byInvalidStatus = new LeadSearchCriteria();
        byInvalidStatus.setStatus(LeadStatus.INVALID);

        LeadSearchCriteria byInvalidValidity = new LeadSearchCriteria();
        byInvalidValidity.setLeadValidity(LeadValidity.INVALID);

        LeadSearchCriteria byConvertedName = new LeadSearchCriteria();
        byConvertedName.setSearch("Converted");

        // "VALID" group includes converted leads in the display mapping - the
        // page must still only show the unconverted one.
        LeadSearchCriteria byValidGroup = new LeadSearchCriteria();
        byValidGroup.setStatusGroup(LeadStatus.VALID);

        assertThat(leadService.searchActiveLeads(byInvalidGroup, 0, 50, null, null).getContent()).isEmpty();
        assertThat(leadService.searchActiveLeads(byInvalidStatus, 0, 50, null, null).getContent()).isEmpty();
        assertThat(leadService.searchActiveLeads(byInvalidValidity, 0, 50, null, null).getContent()).isEmpty();
        assertThat(leadService.searchActiveLeads(byConvertedName, 0, 50, null, null).getContent()).isEmpty();
        assertThat(names(leadService.searchActiveLeads(byValidGroup, 0, 50, null, null))).containsExactly("Valid Co");
    }

    @Test
    void aLeadLeavesTheLeadsPage_whenMarkedInvalid() {

        assertThat(names(leadService.searchActiveLeads(new LeadSearchCriteria(), 0, 50, null, null)))
                .contains("New Co");

        LeadRequest toInvalid = request("New Co", LeadStatus.INVALID, marketing);
        toInvalid.setInvalidReason("Not a real company");
        leadService.updateLead(newLead.getId(), toInvalid);

        assertThat(names(leadService.searchActiveLeads(new LeadSearchCriteria(), 0, 50, null, null)))
                .doesNotContain("New Co");
        assertThat(names(leadService.searchLeadsInCategories(
                new LeadSearchCriteria(), EnumSet.of(LeadCategory.INVALID), 0, 50, null, null)))
                .contains("New Co", "Invalid Co");
    }

    @Test
    void aValidLeadLeavesTheLeadsPage_whenConverted_andCannotBeConvertedTwice() {

        assertThat(names(leadService.searchActiveLeads(new LeadSearchCriteria(), 0, 50, null, null)))
                .contains("Valid Co");

        opportunityService.convertLeadToOpportunity(validLead.getId(), oppRequest());

        assertThat(names(leadService.searchActiveLeads(new LeadSearchCriteria(), 0, 50, null, null)))
                .doesNotContain("Valid Co");

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(validLead.getId(), oppRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been converted");
    }

    @Test
    void aNewLeadCannotBeConverted_untilItIsMarkedValid() {

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(newLead.getId(), oppRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VALID");
    }

    // ===================================================== reporting keeps every category

    @Test
    void report_allCategories_includesInvalidAndConverted() {

        Page<Lead> all = leadService.searchLeadsInCategories(new LeadSearchCriteria(), null, 0, 50, null, null);

        assertThat(all.getTotalElements()).isEqualTo(7);
        assertThat(names(all)).contains("Invalid Co", "Converted Co");
    }

    @Test
    void report_eachCategoryReturnsExactlyItsLeads() {

        assertThat(inCategory(LeadCategory.NEW)).containsExactlyInAnyOrder("New Co", "Legacy Co", "Null Co", "Others Co");
        assertThat(inCategory(LeadCategory.VALID_UNCONVERTED)).containsExactly("Valid Co");
        assertThat(inCategory(LeadCategory.INVALID)).containsExactly("Invalid Co");
        assertThat(inCategory(LeadCategory.CONVERTED)).containsExactly("Converted Co");
    }

    @Test
    void report_categoriesAreMutuallyExclusive_andCountsSumToTheTotal() {

        Map<String, Long> counts = leadService.countByCategory(new LeadSearchCriteria());

        assertThat(counts).containsEntry("NEW", 4L)
                .containsEntry("VALID_UNCONVERTED", 1L)
                .containsEntry("INVALID", 1L)
                .containsEntry("CONVERTED", 1L)
                .containsEntry("TOTAL", 7L);

        assertThat(counts.get("TOTAL")).isEqualTo(leadRepository.count());
    }

    @Test
    void anInvalidLeadThatAlsoHasAnOpportunity_countsOnceAsInvalid() {

        // Historical data can contain both; INVALID wins (same precedence as
        // LeadWorkflow.effectiveStatus) so the lead is never double-counted.
        LeadRequest toInvalid = request("Converted Co", LeadStatus.INVALID, marketing);
        toInvalid.setInvalidReason("Turned out to be spam");
        leadService.updateLead(convertedLead.getId(), toInvalid);

        Map<String, Long> counts = leadService.countByCategory(new LeadSearchCriteria());

        assertThat(counts).containsEntry("INVALID", 2L).containsEntry("CONVERTED", 0L).containsEntry("TOTAL", 7L);
    }

    @Test
    void report_countsRespectTheCallersScope() {

        authenticateAs(marketing);

        Map<String, Long> counts = leadService.countByCategory(new LeadSearchCriteria());

        // marketing owns 6 of the 7 leads (not "Others Co")
        assertThat(counts).containsEntry("TOTAL", 6L).containsEntry("NEW", 3L);
        assertThat(names(leadService.searchLeadsInCategories(new LeadSearchCriteria(), null, 0, 50, null, null)))
                .doesNotContain("Others Co");
    }

    @Test
    void invalidAndConvertedLeads_stillOpenByIdForHistory() {

        assertThat(leadService.getLeadById(invalidLead.getId()).getLeadStatus()).isEqualTo(LeadStatus.INVALID);

        Lead converted = leadService.getLeadById(convertedLead.getId());

        assertThat(converted.getConverted()).isTrue();
        assertThat(converted.getCategory()).isEqualTo(LeadCategory.CONVERTED);
    }

    @Test
    void dashboardLeadCount_matchesTheLeadsPage() {

        assertThat(dashboardService.getStats().getLeads()).isEqualTo(5);

        authenticateAs(marketing);

        assertThat(dashboardService.getStats().getLeads()).isEqualTo(4);
    }

    // ===================================================== export authorization and scope

    @Test
    void export_defaultsToTheOperationalList_andAllCategoriesIsExplicit() {

        authenticateAs(admin);

        assertThat(exportNames(LeadCategory.ACTIVE))
                .containsExactlyInAnyOrder("New Co", "Valid Co", "Legacy Co", "Null Co", "Others Co");

        assertThat(leadService.findForExport(new LeadSearchCriteria(), null, null, null))
                .extracting(Lead::getCompanyName)
                .doesNotContain("Invalid Co", "Converted Co");

        assertThat(exportNames(null)).hasSize(7).contains("Invalid Co", "Converted Co");
        assertThat(exportNames(EnumSet.of(LeadCategory.INVALID))).containsExactly("Invalid Co");
        assertThat(exportNames(EnumSet.of(LeadCategory.CONVERTED))).containsExactly("Converted Co");
    }

    @Test
    void export_labelsEachRowWithItsCategory() {

        List<Lead> all = leadService.findForExport(new LeadSearchCriteria(), null, null, null, null);

        Map<String, LeadCategory> byName = new HashMap<>();
        all.forEach(l -> byName.put(l.getCompanyName(), l.getCategory()));

        assertThat(byName)
                .containsEntry("New Co", LeadCategory.NEW)
                .containsEntry("Legacy Co", LeadCategory.NEW)
                .containsEntry("Valid Co", LeadCategory.VALID_UNCONVERTED)
                .containsEntry("Invalid Co", LeadCategory.INVALID)
                .containsEntry("Converted Co", LeadCategory.CONVERTED);
    }

    @Test
    void export_marketingGetsOnlyTheirOwnRecords_inEveryCategory() {

        authenticateAs(marketing);

        assertThat(exportNames(null))
                .containsExactlyInAnyOrder("New Co", "Valid Co", "Invalid Co", "Converted Co", "Legacy Co", "Null Co")
                .doesNotContain("Others Co");

        assertThat(exportNames(EnumSet.of(LeadCategory.INVALID))).containsExactly("Invalid Co");
    }

    @Test
    void export_aCallerCannotWidenScopeByIdsOrAssigneeFilter() {

        authenticateAs(marketing);

        // Asking for the other marketer's lead by id returns nothing for it.
        List<Lead> byId = leadService.findForExport(
                new LeadSearchCriteria(), List.of(othersNewLead.getId(), newLead.getId()), null, null, null);

        assertThat(byId).extracting(Lead::getCompanyName).containsExactly("New Co");

        // Filtering on someone else's employee id yields nothing.
        LeadSearchCriteria other = new LeadSearchCriteria();
        other.setAssignedEmployeeId(otherMarketing.getId());

        assertThat(leadService.findForExport(other, null, null, null, null)).isEmpty();
    }

    @Test
    void export_aPlainEmployeeAndAManagerAreDenied() {

        authenticateAs(employee);
        assertThatThrownBy(() -> leadService.findForExport(new LeadSearchCriteria(), null, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> opportunityService.findForExport(new OpportunitySearchCriteria(), null, null, null))
                .isInstanceOf(AccessDeniedException.class);

        authenticateAs(manager);
        assertThatThrownBy(() -> leadService.findForExport(new LeadSearchCriteria(), null, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> opportunityService.findForExport(new OpportunitySearchCriteria(), null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void export_scopeIsTheNarrowerOfTheViewAndExportGrants() {

        // An export grant wider than the view grant must not widen what is
        // exported: give a role export at ALL but view only at OWN.
        Role odd = roleRepository.save(Role.builder().name("ODD").rank(10).build());
        grant(odd, LEAD_VIEW, Scope.OWN);
        grant(odd, LEAD_EXPORT, Scope.ALL);
        Employee oddUser = employee("Odd", "odd@t.com", "9500000009", odd, null);

        authenticateAs(oddUser);

        assertThat(leadService.findForExport(new LeadSearchCriteria(), null, null, null, null)).isEmpty();
    }

    @Test
    void opportunityExport_marketingSeesOnlyOpportunitiesOnTheirLeads() {

        // another marketer's converted lead
        Lead others = leadService.createLead(request("Others Valid", LeadStatus.VALID, otherMarketing));
        opportunityService.convertLeadToOpportunity(others.getId(), oppRequest());

        authenticateAs(admin);
        assertThat(opportunityService.findForExport(new OpportunitySearchCriteria(), null, null, null)).hasSize(2);

        authenticateAs(marketing);
        List<Opportunity> mine = opportunityService.findForExport(new OpportunitySearchCriteria(), null, null, null);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getLead().getCompanyName()).isEqualTo("Converted Co");
    }

    @Test
    void marketing_canViewAndExportButStillCannotManageAnotherUsersLead() {

        authenticateAs(marketing);

        assertThatThrownBy(() -> leadService.getLeadById(othersNewLead.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ===================================================== helpers

    private Set<String> inCategory(LeadCategory category) {
        return Set.copyOf(names(leadService.searchLeadsInCategories(
                new LeadSearchCriteria(), EnumSet.of(category), 0, 50, null, null)));
    }

    private List<String> names(Page<Lead> page) {
        return page.getContent().stream().map(Lead::getCompanyName).toList();
    }

    private List<String> exportNames(Set<LeadCategory> categories) {
        return leadService.findForExport(new LeadSearchCriteria(), null, null, null, categories)
                .stream().map(Lead::getCompanyName).toList();
    }

    private Employee employee(String name, String email, String phone, Role role, Employee boss) {
        return employeeRepository.save(Employee.builder()
                .name(name).email(email).phone(phone).password("x").role(role).manager(boss).build());
    }

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissions.computeIfAbsent(
                code, c -> permissionRepository.save(Permission.builder().code(c).build()));

        rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
    }

    private void authenticateAs(Employee actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }

    private LeadRequest request(String company, LeadStatus status, Employee owner) {

        LeadRequest r = new LeadRequest();
        r.setCompanyName(company);
        r.setContactPerson("Contact");
        r.setDesignation("Manager");
        r.setPhone("98765" + String.format("%05d", Math.abs(company.hashCode()) % 100000));
        r.setCity("Pune");
        r.setState("MH");
        r.setIndustryId(industry.getId());
        r.setLeadSourceId(source.getId());
        r.setLeadStatus(status);
        r.setAssignedEmployeeId(owner.getId());
        return r;
    }

    private Lead stored(String company, LeadStatus status, Employee owner) {

        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("C")
                .phone("9" + String.format("%09d", Math.abs(company.hashCode()) % 1000000000))
                .email(company.toLowerCase().replace(" ", "") + "@x.test")
                .city("Pune").state("MH").industry(industry).leadSource(source)
                .leadStatus(status).leadValidity(LeadValidity.VALID).assignedEmployee(owner).build());
    }

    private OpportunityRequest oppRequest() {

        OpportunityRequest r = new OpportunityRequest();
        r.setTitle("Deal");
        r.setExpectedClosingDate(BusinessClock.today().plusDays(30));
        r.setSalesStage("IN PROGRESS");
        r.setLeadValidity(LeadValidity.VALID);
        r.setNextActionDescription("Call to discuss the proposal");
        r.setNextFollowUpDate(LocalDateTime.now().plusDays(7));
        r.setNextActionActivityTypeId(call.getId());
        return r;
    }
}
