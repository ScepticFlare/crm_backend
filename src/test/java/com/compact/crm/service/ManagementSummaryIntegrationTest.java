package com.compact.crm.service;

import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.response.ManagementSummaryResponse;
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
import com.compact.crm.enums.OpportunitySubStatus;
import com.compact.crm.enums.SalesStageType;
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
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.compact.crm.security.AccessControlService.FOLLOWUP_VIEW;
import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the management summary ACTUALLY computes, against a small
 * dataset with known answers - and that it respects the Admin / Manager /
 * Employee visibility scopes.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, FollowUpService.class, CustomerService.class, SalesStageService.class,
        ActivityLogService.class, AccessControlService.class, CurrentUserService.class,
        ManagementSummaryService.class})
class ManagementSummaryIntegrationTest {

    @Autowired private ManagementSummaryService summaryService;
    @Autowired private OpportunityService opportunityService;
    @Autowired private SalesStageService salesStageService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository sourceRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;

    private Employee admin, manager, e1, e2;
    private Industry industry;
    private LeadSourceMaster web, referral;
    private ActivityType call;
    private Opportunity slipping;   // the opportunity whose closing date changes twice

    private final Map<String, Permission> permissions = new HashMap<>();

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        for (String code : List.of(LEAD_MANAGE, LEAD_VIEW, OPPORTUNITY_MANAGE, OPPORTUNITY_VIEW, FOLLOWUP_VIEW)) {
            grant(adminRole, code, Scope.ALL);
            grant(managerRole, code, Scope.TEAM);
            grant(employeeRole, code, Scope.OWN);
        }

        admin = employeeRepository.save(Employee.builder().name("Admin").email("a@t.com").phone("9500000001")
                .password("x").role(adminRole).build());
        manager = employeeRepository.save(Employee.builder().name("Manager").email("m@t.com").phone("9500000002")
                .password("x").role(managerRole).build());
        e1 = employeeRepository.save(Employee.builder().name("Asha").email("e1@t.com").phone("9500000003")
                .password("x").role(employeeRole).manager(manager).build());
        e2 = employeeRepository.save(Employee.builder().name("Ravi").email("e2@t.com").phone("9500000004")
                .password("x").role(employeeRole).build());

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        web = sourceRepository.save(LeadSourceMaster.builder().name("Web").build());
        referral = sourceRepository.save(LeadSourceMaster.builder().name("Referral").build());
        call = activityTypeRepository.save(ActivityType.builder().name("Call").build());

        salesStageService.ensureCoreStages();

        LocalDate today = BusinessClock.today();

        // L1: NEW, contact deadline yesterday, never contacted -> uncontacted
        Lead l1 = lead("L1", LeadStatus.NEW, e1, web);
        l1.setContactDeadline(today.minusDays(1));
        leadRepository.save(l1);

        // L2: VALID with an active opportunity at QUOTATION_SENT closing in 5 days (value 1000)
        opp(lead("L2", LeadStatus.VALID, e1, web), SalesStageType.IN_PROGRESS, OpportunitySubStatus.QUOTATION_SENT,
                today.plusDays(5), 1000.0, null);

        // L3: INVALID
        Lead l3 = lead("L3", LeadStatus.INVALID, e1, web);
        l3.setLeadValidity(LeadValidity.INVALID);
        leadRepository.save(l3);

        // L4: owned by e2, WON (value 5000), via Referral
        opp(lead("L4", LeadStatus.VALID, e2, referral), SalesStageType.WON, null, null, 5000.0, null);

        // L5/L6/L7: LOST - "Price" (e1), "price " (e2, different case/space), and one legacy with no reason (e1)
        opp(lead("L5", LeadStatus.VALID, e1, web), SalesStageType.LOST, null, null, 10.0, "Price");
        opp(lead("L6", LeadStatus.VALID, e2, web), SalesStageType.LOST, null, null, 10.0, "price ");
        opp(lead("L7", LeadStatus.VALID, e1, web), SalesStageType.LOST, null, null, 10.0, null);

        // L8: active, closing date 3 days ago (overdue), value 200, with an overdue pending follow-up
        Opportunity overdue = opp(lead("L8", LeadStatus.VALID, e1, web), SalesStageType.IN_PROGRESS, null,
                today.minusDays(3), 200.0, null);
        followUpRepository.save(FollowUp.builder().opportunity(overdue).employee(e1).activityType(call)
                .status(FollowUpStatus.PENDING).scheduledDate(LocalDateTime.now().minusDays(2)).build());

        // L9: active, closes in 20 days (value 300) - its date is then changed twice through the service
        slipping = opp(lead("L9", LeadStatus.VALID, e1, web), SalesStageType.IN_PROGRESS, null,
                today.plusDays(20), 300.0, null);

        authenticateAs(admin);

        // active opportunities need a next action to be edited
        followUpRepository.save(FollowUp.builder()
                .lead(slipping.getLead()).employee(admin).activityType(call)
                .status(FollowUpStatus.PENDING)
                .scheduledDate(LocalDateTime.now().plusDays(10))
                .remarks("Confirm the revised delivery date").build());

        for (int d : new int[]{21, 22}) {

            OpportunityRequest r = new OpportunityRequest();
            r.setTitle(slipping.getTitle());
            r.setProductValue(300.0);
            r.setExpectedClosingDate(today.plusDays(d));
            r.setSalesStage("IN PROGRESS");
            r.setLeadValidity(LeadValidity.VALID);
            r.setClosingDateChangeReason("customer delay " + d);

            opportunityService.updateOpportunity(slipping.getId(), r);
        }
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

    private Lead lead(String name, LeadStatus status, Employee owner, LeadSourceMaster source) {

        return leadRepository.save(Lead.builder()
                .companyName(name).contactPerson("C").phone("9" + String.format("%09d", Math.abs(name.hashCode()) % 1000000000))
                .city("Pune").state("MH").industry(industry).leadSource(source)
                .leadStatus(status).leadValidity(LeadValidity.VALID).assignedEmployee(owner)
                .createdAt(LocalDateTime.now()).build());
    }

    private Opportunity opp(Lead lead, SalesStageType type, OpportunitySubStatus sub, LocalDate closing,
                            double value, String lostReason) {

        return opportunityRepository.save(Opportunity.builder()
                .title(lead.getCompanyName() + " deal").productValue(value).expectedClosingDate(closing)
                .salesStage(salesStageService.getByType(type)).subStatus(sub).lostReason(lostReason)
                .leadValidity(LeadValidity.VALID).lead(lead).build());
    }

    private ManagementSummaryResponse summaryAs(Employee actor) {

        authenticateAs(actor);

        return summaryService.getSummary(null, null);
    }

    // ===================================================== admin sees everything

    @Test
    void funnel_countsLeadsAsTheyAreDisplayed_andTheValidRate() {

        ManagementSummaryResponse.Funnel f = summaryAs(admin).funnel();

        assertThat(f.leadsReceived()).isEqualTo(9);
        assertThat(f.invalidLeads()).isEqualTo(1);        // L3
        assertThat(f.validLeads()).isEqualTo(7);          // L2,L4,L5,L6,L7,L8,L9
        assertThat(f.newLeads()).isEqualTo(1);            // L1
        assertThat(f.validRatePercent()).isEqualTo(77.78);
        assertThat(f.convertedToOpportunity()).isEqualTo(7);
        assertThat(f.currentlyQuotationSent()).isEqualTo(1);
        assertThat(f.won()).isEqualTo(1);
        assertThat(f.newLeadsNow()).isEqualTo(1);

        // the three display groups partition the leads
        assertThat(f.newLeads() + f.validLeads() + f.invalidLeads()).isEqualTo(f.leadsReceived());
    }

    @Test
    void funnel_respectsTheDateRange() {

        authenticateAs(admin);

        ManagementSummaryResponse past = summaryService.getSummary(
                BusinessClock.today().minusDays(60), BusinessClock.today().minusDays(40));

        assertThat(past.funnel().leadsReceived()).isZero();
        assertThat(past.funnel().validRatePercent()).isZero();   // no division by zero
    }

    @Test
    void pipeline_totalsOnlyActiveOpportunities() {

        ManagementSummaryResponse.Pipeline p = summaryAs(admin).pipeline();

        assertThat(p.openCount()).isEqualTo(3);                  // L2, L8, L9 (not WON / LOST)
        assertThat(p.openValue()).isEqualTo(1500.0);
        assertThat(p.byStage()).hasSize(1);
        assertThat(p.byStage().get(0).stage()).isEqualTo("IN PROGRESS");
        assertThat(p.byStage().get(0).count()).isEqualTo(3);
    }

    @Test
    void overdue_opportunitiesAndFollowUps() {

        ManagementSummaryResponse.Overdue o = summaryAs(admin).overdue();

        assertThat(o.opportunityCount()).isEqualTo(1);
        assertThat(o.opportunityValue()).isEqualTo(200.0);
        assertThat(o.opportunities()).extracting(ManagementSummaryResponse.OpportunityItem::title)
                .containsExactly("L8 deal");
        assertThat(o.opportunities().get(0).owner()).isEqualTo("Asha");

        assertThat(o.followUpCount()).isEqualTo(1);
        assertThat(o.followUps().get(0).company()).isEqualTo("L8");
    }

    @Test
    void closingWindows_7_15_30_days() {

        List<ManagementSummaryResponse.ClosingWindow> w = summaryAs(admin).closingSoon();

        assertThat(w).extracting(ManagementSummaryResponse.ClosingWindow::days).containsExactly(7, 15, 30);

        // L2 closes in 5 days (1000); L9 is now ~22 days out (300); L8 is overdue so is in no window
        assertThat(w.get(0).count()).isEqualTo(1);
        assertThat(w.get(0).value()).isEqualTo(1000.0);
        assertThat(w.get(1).count()).isEqualTo(1);
        assertThat(w.get(2).count()).isEqualTo(2);
        assertThat(w.get(2).value()).isEqualTo(1300.0);
    }

    @Test
    void winsAndLosses_bySalespersonAndBySource() {

        ManagementSummaryResponse r = summaryAs(admin);

        Map<String, ManagementSummaryResponse.OwnerResult> byPerson = new HashMap<>();
        r.resultsBySalesperson().forEach(x -> byPerson.put(x.salesperson(), x));

        assertThat(byPerson.get("Ravi").won()).isEqualTo(1);
        assertThat(byPerson.get("Ravi").wonValue()).isEqualTo(5000.0);
        assertThat(byPerson.get("Ravi").lost()).isEqualTo(1);     // L6
        assertThat(byPerson.get("Asha").won()).isZero();
        assertThat(byPerson.get("Asha").lost()).isEqualTo(2);     // L5, L7
        assertThat(r.resultsBySalesperson().get(0).salesperson()).isEqualTo("Ravi");   // most wins first

        Map<String, ManagementSummaryResponse.SourceResult> bySource = new HashMap<>();
        r.resultsBySource().forEach(x -> bySource.put(x.source(), x));

        assertThat(bySource.get("Referral").won()).isEqualTo(1);
        assertThat(bySource.get("Referral").wonValue()).isEqualTo(5000.0);
        assertThat(bySource.get("Web").lost()).isEqualTo(3);
    }

    @Test
    void lossReasons_groupedCaseAndSpaceInsensitively_withLegacyBlanksCountedSeparately() {

        ManagementSummaryResponse.LossReasons lr = summaryAs(admin).lossReasons();

        assertThat(lr.reasons()).hasSize(1);
        assertThat(lr.reasons().get(0).reason()).isEqualToIgnoringCase("price");
        assertThat(lr.reasons().get(0).count()).isEqualTo(2);
        assertThat(lr.lostWithoutReason()).isEqualTo(1);
    }

    @Test
    void closureDateChanges_flagsOpportunitiesChangedTwiceOrMore() {

        ManagementSummaryResponse.ClosureDateChanges c = summaryAs(admin).closureDateChanges();

        assertThat(c.opportunitiesWithRepeatedChanges()).isEqualTo(1);
        assertThat(c.top().get(0).opportunityId()).isEqualTo(slipping.getId());
        assertThat(c.top().get(0).changes()).isEqualTo(2);
    }

    @Test
    void uncontactedLeads_newLeadsPastTheirDeadlineWithoutACompletedFollowUp() {

        ManagementSummaryResponse.UncontactedLeads u = summaryAs(admin).uncontactedLeads();

        assertThat(u.count()).isEqualTo(1);
        assertThat(u.leads().get(0).company()).isEqualTo("L1");
        assertThat(u.leads().get(0).owner()).isEqualTo("Asha");

        // once the lead is actually contacted (a completed follow-up) it drops out
        Lead l1 = leadRepository.findAll().stream().filter(l -> l.getCompanyName().equals("L1")).findFirst().orElseThrow();
        followUpRepository.save(FollowUp.builder().lead(l1).employee(e1).activityType(call)
                .status(FollowUpStatus.COMPLETED).scheduledDate(LocalDateTime.now().minusHours(3)).build());

        assertThat(summaryAs(admin).uncontactedLeads().count()).isZero();
    }

    // ===================================================== visibility scope

    @Test
    void employee_seesOnlyTheirOwnNumbers() {

        ManagementSummaryResponse r = summaryAs(e2);   // Ravi owns L4 (WON 5000) and L6 (LOST)

        assertThat(r.funnel().leadsReceived()).isEqualTo(2);
        assertThat(r.funnel().won()).isEqualTo(1);
        assertThat(r.pipeline().openCount()).isZero();
        assertThat(r.overdue().opportunityCount()).isZero();
        assertThat(r.overdue().followUpCount()).isZero();
        assertThat(r.resultsBySalesperson()).extracting(ManagementSummaryResponse.OwnerResult::salesperson)
                .containsExactly("Ravi");
        assertThat(r.lossReasons().reasons().get(0).count()).isEqualTo(1);
        assertThat(r.closureDateChanges().opportunitiesWithRepeatedChanges()).isZero();
        assertThat(r.uncontactedLeads().count()).isZero();
    }

    @Test
    void manager_seesTheirTeamOnly() {

        ManagementSummaryResponse team = summaryAs(manager);   // team = Manager + Asha (not Ravi)

        assertThat(team.funnel().leadsReceived()).isEqualTo(7);   // 9 minus Ravi's L4, L6
        assertThat(team.funnel().won()).isZero();
        assertThat(team.pipeline().openCount()).isEqualTo(3);
        assertThat(team.resultsBySalesperson()).extracting(ManagementSummaryResponse.OwnerResult::salesperson)
                .containsExactly("Asha");
        assertThat(team.closureDateChanges().opportunitiesWithRepeatedChanges()).isEqualTo(1);
        assertThat(team.uncontactedLeads().count()).isEqualTo(1);
    }

    @Test
    void admin_seesEveryone() {

        ManagementSummaryResponse all = summaryAs(admin);

        assertThat(all.resultsBySalesperson()).extracting(ManagementSummaryResponse.OwnerResult::salesperson)
                .containsExactlyInAnyOrder("Ravi", "Asha");
        assertThat(all.notes()).isNotEmpty();
    }
}
