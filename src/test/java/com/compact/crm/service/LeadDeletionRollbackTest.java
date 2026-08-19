package com.compact.crm.service;

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
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Scope;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.List;

import static com.compact.crm.security.AccessControlService.CUSTOMER_DELETE;
import static com.compact.crm.security.AccessControlService.LEAD_DELETE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_DELETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Requirement J: a failure midway through the cascade must roll back the
 * *entire* delete - no partially-deleted chain. Kept in its own file/context
 * (rather than folded into LeadDeletionCascadeIntegrationTest) because it
 * needs FollowUpRepository replaced with a mock that fails on demand - doing
 * that in the shared cascade test class would break every other test there
 * that relies on FollowUpRepository actually working.
 *
 * The tricky part of proving this with a real @Transactional service bean:
 * deleteLead's @Transactional method runs *inside* the single transaction
 * @DataJpaTest already opens for the test method, so it only *joins* that
 * transaction rather than owning it - a thrown exception marks it
 * rollback-only but does not immediately issue a physical ROLLBACK. Reading
 * "did the customer actually get un-deleted" from within that same
 * transaction would therefore see the mid-flight (uncommitted but not yet
 * rolled back) state, not the truth. TestTransaction.end()/start() is used
 * to force the real rollback to happen, then open a fresh transaction to
 * observe the genuinely post-rollback state.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, CustomerService.class, SalesStageService.class,
        ActivityLogService.class, AccessControlService.class, CurrentUserService.class})
class LeadDeletionRollbackTest {

    @Autowired private LeadService leadService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private SalesStageRepository salesStageRepository;

    // Replaces the real FollowUpRepository bean for this whole test class -
    // lets the cascade reach "delete a follow-up" and fail there on demand,
    // simulating a genuine mid-chain failure (after the Customer has
    // already been deleted for real, before the Opportunity/Lead are).
    @MockBean
    private FollowUpRepository followUpRepository;

    @Test
    void midCascadeFailure_rollsBackTheEntireChain_noPartialDeletionRemains() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        grant(adminRole, LEAD_DELETE);
        grant(adminRole, OPPORTUNITY_DELETE);
        grant(adminRole, CUSTOMER_DELETE);

        Employee admin = employeeRepository.save(Employee.builder()
                .name("Admin").email("admin@test.com").phone("9000000001").password("x").role(adminRole).build());

        Industry industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        LeadSourceMaster leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());
        SalesStage wonStage = salesStageRepository.save(SalesStage.builder().name("WON").build());

        Lead lead = leadRepository.save(Lead.builder()
                .companyName("Fragile Co").contactPerson("Contact").phone("1234567890")
                .email("fragile@example.com").city("Pune").state("MH")
                .industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.NEW).leadValidity(LeadValidity.VALID)
                .assignedEmployee(admin).build());

        Opportunity opportunity = opportunityRepository.save(Opportunity.builder()
                .title("Fragile Co Deal").productValue(1000.0).salesStage(wonStage)
                .leadValidity(LeadValidity.VALID).lead(lead).build());

        Customer customer = customerRepository.save(Customer.builder()
                .customerCode("CUST-ROLLBACK").companyName("Fragile Co")
                .contactPerson("Contact").phone("1234567890").email("customer-rollback@example.com")
                .assignedEmployee(admin).opportunity(opportunity).build());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null, List.of()));

        // Commit the setup for real, then start a fresh transaction for the
        // delete attempt - see class javadoc for why this is necessary.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        // The opportunity has one follow-up (per findByOpportunityId), and
        // deleting it always blows up - simulates a genuine failure partway
        // through cascadeDeleteOpportunity, *after* the real customer
        // delete above it has already executed.
        FollowUp doomedFollowUp = FollowUp.builder().id(999L).opportunity(opportunity).build();

        when(followUpRepository.findByOpportunityId(opportunity.getId())).thenReturn(List.of(doomedFollowUp));
        when(followUpRepository.findByLeadId(anyLong())).thenReturn(List.of());
        doThrow(new RuntimeException("simulated mid-cascade failure"))
                .when(followUpRepository).delete(any(FollowUp.class));

        Long leadId = lead.getId();
        Long opportunityId = opportunity.getId();
        Long customerId = customer.getId();

        assertThatThrownBy(() -> leadService.deleteLead(leadId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated mid-cascade failure");

        // Force the real rollback (see class javadoc), then read from a
        // brand new transaction so we observe genuinely post-rollback state.
        TestTransaction.end();
        TestTransaction.start();

        assertThat(leadRepository.findById(leadId))
                .as("Lead must survive a failed cascade delete")
                .isPresent();

        assertThat(opportunityRepository.findById(opportunityId))
                .as("Opportunity must survive - its delete never even ran")
                .isPresent();

        assertThat(customerRepository.findById(customerId))
                .as("Customer's delete DID execute before the failure, but must be rolled back - " +
                        "this is the crux of requirement J")
                .isPresent();
    }

    private void grant(Role role, String permissionCode) {

        Permission permission = permissionRepository.save(Permission.builder().code(permissionCode).build());

        rolePermissionRepository.save(RolePermission.builder()
                .role(role).permission(permission).scope(Scope.ALL).build());
    }
}
