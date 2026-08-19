package com.compact.crm.service;

import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.CustomerRepository;
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
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static com.compact.crm.security.AccessControlService.CUSTOMER_DELETE;
import static com.compact.crm.security.AccessControlService.LEAD_DELETE;
import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_DELETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real end-to-end verification of the Admin cascade-delete workflow (Lead ->
 * Opportunity -> Customer/Won -> FollowUps), against a real H2 database with
 * the real LeadService/OpportunityService/CustomerService beans - not
 * Mockito mocks. This is deliberate: the whole point of this feature is that
 * deleting a parent record no longer trips a real foreign-key constraint on
 * its children, and only a real database (H2 here, matching Postgres'
 * default RESTRICT behavior on an unannotated @ManyToOne FK) can actually
 * prove that. A Mockito-based unit test of these services would only prove
 * "the right repository methods were called in some order," not that the
 * generated SQL/FK chain is actually satisfiable.
 *
 * See LeadDeletionRollbackTest for the dedicated failure/rollback scenario -
 * kept in its own file since it needs to replace FollowUpRepository with a
 * mock that would break every other test in this class if shared here.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, CustomerService.class, SalesStageService.class,
        ActivityLogService.class, AccessControlService.class, CurrentUserService.class})
class LeadDeletionCascadeIntegrationTest {

    @Autowired private LeadService leadService;
    @Autowired private OpportunityService opportunityService;
    @Autowired private CustomerService customerService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private SalesStageRepository salesStageRepository;

    private Employee admin;
    private Employee manager;
    private Employee employee;
    private Industry industry;
    private LeadSourceMaster leadSource;
    private ActivityType activityType;

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        grant(adminRole, LEAD_DELETE, Scope.ALL);
        grant(adminRole, OPPORTUNITY_DELETE, Scope.ALL);
        grant(adminRole, CUSTOMER_DELETE, Scope.ALL);

        // Manager/Employee keep their normal MANAGE grants (create/update)
        // but deliberately get no DELETE grant at all - proves H/I (403)
        // reflects the real RBAC split, not a hardcoded rejection.
        grant(managerRole, LEAD_MANAGE, Scope.TEAM);
        grant(employeeRole, LEAD_MANAGE, Scope.OWN);

        admin = employeeRepository.save(Employee.builder()
                .name("Admin").email("admin@test.com").phone("9000000001").password("x").role(adminRole).build());
        manager = employeeRepository.save(Employee.builder()
                .name("Manager").email("manager@test.com").phone("9000000002").password("x").role(managerRole).build());
        employee = employeeRepository.save(Employee.builder()
                .name("Employee").email("employee@test.com").phone("9000000003").password("x")
                .role(employeeRole).manager(manager).build());

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());
        activityType = activityTypeRepository.save(ActivityType.builder().name("Call").build());

        authenticateAs(admin);
    }

    private final java.util.Map<String, Permission> permissionsByCode = new java.util.HashMap<>();

    // permissions.code is unique - reuse the same row across multiple
    // grant() calls for the same code (e.g. LEAD_MANAGE granted to both
    // Manager and Employee) instead of inserting a duplicate.
    private void grant(Role role, String permissionCode, Scope scope) {

        Permission permission = permissionsByCode.computeIfAbsent(
                permissionCode, code -> permissionRepository.save(Permission.builder().code(code).build()));

        rolePermissionRepository.save(RolePermission.builder()
                .role(role).permission(permission).scope(scope).build());
    }

    private void authenticateAs(Employee actor) {

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }

    private Lead saveLead(String companyName) {

        return leadRepository.save(Lead.builder()
                .companyName(companyName).contactPerson("Contact").phone("1234567890")
                .email(companyName.toLowerCase() + "@example.com")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.NEW).leadValidity(LeadValidity.VALID)
                .assignedEmployee(admin).build());
    }

    private Opportunity saveOpportunity(Lead lead, String stageName) {

        SalesStage stage = salesStageRepository.save(SalesStage.builder().name(stageName).build());

        return opportunityRepository.save(Opportunity.builder()
                .title(lead.getCompanyName() + " Deal")
                .productValue(1000.0)
                .salesStage(stage)
                .leadValidity(LeadValidity.VALID)
                .lead(lead)
                .build());
    }

    private Customer saveCustomer(Opportunity opportunity) {

        return customerRepository.save(Customer.builder()
                .customerCode("CUST-" + opportunity.getId())
                .companyName(opportunity.getTitle())
                .contactPerson("Contact")
                .phone("1234567890")
                .email("customer" + opportunity.getId() + "@example.com")
                .assignedEmployee(admin)
                .opportunity(opportunity)
                .build());
    }

    private FollowUp saveFollowUpOnLead(Lead lead) {

        return followUpRepository.save(FollowUp.builder()
                .lead(lead).employee(admin).activityType(activityType)
                .status(FollowUpStatus.PENDING)
                .scheduledDate(LocalDateTime.now().plusDays(1))
                .build());
    }

    private FollowUp saveFollowUpOnOpportunity(Opportunity opportunity) {

        return followUpRepository.save(FollowUp.builder()
                .opportunity(opportunity).employee(admin).activityType(activityType)
                .status(FollowUpStatus.PENDING)
                .scheduledDate(LocalDateTime.now().plusDays(1))
                .build());
    }

    // ---------- A ----------

    @Test
    void leadWithNoDependencies_adminDeletesSuccessfully() {

        Lead lead = saveLead("NoDeps Co");

        leadService.deleteLead(lead.getId());

        assertThat(leadRepository.findById(lead.getId())).isEmpty();
    }

    // ---------- B ----------

    @Test
    void leadWithFollowUps_adminDeletesLeadAndItsFollowUps() {

        Lead lead = saveLead("FollowedUp Co");
        FollowUp f1 = saveFollowUpOnLead(lead);
        FollowUp f2 = saveFollowUpOnLead(lead);

        leadService.deleteLead(lead.getId());

        assertThat(leadRepository.findById(lead.getId())).isEmpty();
        assertThat(followUpRepository.findById(f1.getId())).isEmpty();
        assertThat(followUpRepository.findById(f2.getId())).isEmpty();
    }

    // ---------- C ----------

    @Test
    void leadWithOpportunity_adminDeletesLeadWithoutManuallyDeletingOpportunityFirst() {

        Lead lead = saveLead("Progressing Co");
        Opportunity opportunity = saveOpportunity(lead, "NEW");

        leadService.deleteLead(lead.getId());

        assertThat(leadRepository.findById(lead.getId())).isEmpty();
        assertThat(opportunityRepository.findById(opportunity.getId())).isEmpty();
    }

    // ---------- D ----------

    @Test
    void leadWonChain_adminDeletesLeadOpportunityAndCustomerTogether() {

        Lead lead = saveLead("Won Co");
        Opportunity opportunity = saveOpportunity(lead, "WON");
        Customer customer = saveCustomer(opportunity);

        leadService.deleteLead(lead.getId());

        assertThat(leadRepository.findById(lead.getId())).isEmpty();
        assertThat(opportunityRepository.findById(opportunity.getId())).isEmpty();
        assertThat(customerRepository.findById(customer.getId())).isEmpty();
    }

    // ---------- E ----------

    @Test
    void leadChainWithFollowUpsOnBothLeadAndOpportunity_allAreDeleted() {

        Lead lead = saveLead("Busy Co");
        Opportunity opportunity = saveOpportunity(lead, "NEGOTIATION");

        FollowUp leadFollowUp1 = saveFollowUpOnLead(lead);
        FollowUp leadFollowUp2 = saveFollowUpOnLead(lead);
        FollowUp oppFollowUp1 = saveFollowUpOnOpportunity(opportunity);
        FollowUp oppFollowUp2 = saveFollowUpOnOpportunity(opportunity);

        leadService.deleteLead(lead.getId());

        assertThat(leadRepository.findById(lead.getId())).isEmpty();
        assertThat(opportunityRepository.findById(opportunity.getId())).isEmpty();
        assertThat(followUpRepository.findById(leadFollowUp1.getId())).isEmpty();
        assertThat(followUpRepository.findById(leadFollowUp2.getId())).isEmpty();
        assertThat(followUpRepository.findById(oppFollowUp1.getId())).isEmpty();
        assertThat(followUpRepository.findById(oppFollowUp2.getId())).isEmpty();
    }

    // ---------- F ----------

    @Test
    void opportunityWithCustomerAndFollowUps_adminDeletesFromOpportunityPage_leadSurvives() {

        Lead lead = saveLead("Deal Co");
        Opportunity opportunity = saveOpportunity(lead, "WON");
        Customer customer = saveCustomer(opportunity);
        FollowUp followUp = saveFollowUpOnOpportunity(opportunity);

        opportunityService.deleteOpportunity(opportunity.getId());

        assertThat(opportunityRepository.findById(opportunity.getId())).isEmpty();
        assertThat(customerRepository.findById(customer.getId())).isEmpty();
        assertThat(followUpRepository.findById(followUp.getId())).isEmpty();

        // Deleting an Opportunity must never reach back up and delete the
        // Lead it came from - only its own dependents.
        assertThat(leadRepository.findById(lead.getId())).isPresent();
    }

    // ---------- G ----------

    @Test
    void customerWithOpportunityFollowUps_adminDeletesCustomer_opportunityAndFollowUpsSurvive() {

        Lead lead = saveLead("Won Deal Co");
        Opportunity opportunity = saveOpportunity(lead, "WON");
        Customer customer = saveCustomer(opportunity);
        FollowUp followUp = saveFollowUpOnOpportunity(opportunity);

        customerService.deleteCustomer(customer.getId());

        assertThat(customerRepository.findById(customer.getId())).isEmpty();

        // A Customer is the terminal node of the chain - nothing references
        // it, so deleting it must never touch the Opportunity/Lead/
        // FollowUps it came from.
        assertThat(opportunityRepository.findById(opportunity.getId())).isPresent();
        assertThat(leadRepository.findById(lead.getId())).isPresent();
        assertThat(followUpRepository.findById(followUp.getId())).isPresent();
    }

    // ---------- H ----------

    @Test
    void managerAttemptsDelete_rejected() {

        Lead lead = saveLead("Manager Target Co");
        authenticateAs(manager);

        assertThatThrownBy(() -> leadService.deleteLead(lead.getId()))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(leadRepository.findById(lead.getId())).isPresent();
    }

    // ---------- I ----------

    @Test
    void employeeAttemptsDelete_rejected() {

        Lead lead = saveLead("Employee Target Co");
        authenticateAs(employee);

        assertThatThrownBy(() -> leadService.deleteLead(lead.getId()))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(leadRepository.findById(lead.getId())).isPresent();
    }

    // ---------- K ----------

    @Test
    void leadDeleteImpact_reportsAccurateCounts_forFullWonChain() {

        Lead lead = saveLead("Preview Co");
        Opportunity opportunity = saveOpportunity(lead, "WON");
        saveCustomer(opportunity);
        saveFollowUpOnLead(lead);
        saveFollowUpOnOpportunity(opportunity);
        saveFollowUpOnOpportunity(opportunity);

        var impact = leadService.getDeleteImpact(lead.getId());

        assertThat(impact.getOpportunityCount()).isEqualTo(1);
        assertThat(impact.getCustomerCount()).isEqualTo(1);
        assertThat(impact.getFollowUpCount()).isEqualTo(3);
        assertThat(impact.isWonCustomer()).isTrue();

        // A preview must never itself delete anything.
        assertThat(leadRepository.findById(lead.getId())).isPresent();
        assertThat(opportunityRepository.findById(opportunity.getId())).isPresent();
    }

    @Test
    void leadDeleteImpact_reportsZero_forLeadWithNoDependencies() {

        Lead lead = saveLead("Empty Co");

        var impact = leadService.getDeleteImpact(lead.getId());

        assertThat(impact.getOpportunityCount()).isZero();
        assertThat(impact.getCustomerCount()).isZero();
        assertThat(impact.getFollowUpCount()).isZero();
        assertThat(impact.isWonCustomer()).isFalse();
    }

    @Test
    void opportunityDeleteImpact_reportsAccurateCounts() {

        Lead lead = saveLead("Opp Preview Co");
        Opportunity opportunity = saveOpportunity(lead, "WON");
        saveCustomer(opportunity);
        saveFollowUpOnOpportunity(opportunity);

        var impact = opportunityService.getDeleteImpact(opportunity.getId());

        assertThat(impact.getCustomerCount()).isEqualTo(1);
        assertThat(impact.getFollowUpCount()).isEqualTo(1);
        assertThat(impact.isWonCustomer()).isTrue();
    }

    @Test
    void customerDeleteImpact_alwaysReportsZero() {

        Lead lead = saveLead("Customer Preview Co");
        Opportunity opportunity = saveOpportunity(lead, "WON");
        Customer customer = saveCustomer(opportunity);

        var impact = customerService.getDeleteImpact(customer.getId());

        assertThat(impact.getOpportunityCount()).isZero();
        assertThat(impact.getCustomerCount()).isZero();
        assertThat(impact.getFollowUpCount()).isZero();
        assertThat(impact.isWonCustomer()).isFalse();
    }

    // ---------- bulk-delete cascades too ----------

    @Test
    void bulkDeleteLeads_cascadesFullChainForEachSucceededLead() {

        Lead lead = saveLead("Bulk Won Co");
        Opportunity opportunity = saveOpportunity(lead, "WON");
        Customer customer = saveCustomer(opportunity);
        FollowUp followUp = saveFollowUpOnLead(lead);

        var result = leadService.bulkDeleteLeads(List.of(lead.getId()));

        assertThat(result.getSucceededIds()).containsExactly(lead.getId());
        assertThat(leadRepository.findById(lead.getId())).isEmpty();
        assertThat(opportunityRepository.findById(opportunity.getId())).isEmpty();
        assertThat(customerRepository.findById(customer.getId())).isEmpty();
        assertThat(followUpRepository.findById(followUp.getId())).isEmpty();
    }
}
