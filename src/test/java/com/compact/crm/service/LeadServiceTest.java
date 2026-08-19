package com.compact.crm.service;

import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.dto.search.LeadSearchCriteria;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.repository.BatteryRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.LEAD_DELETE;
import static com.compact.crm.security.AccessControlService.LEAD_EXPORT;
import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end (within the service layer) coverage of the Admin/Manager/
 * Employee scenarios from the RBAC hierarchy task, using a real
 * AccessControlService wired against mocked repositories - not a mocked
 * AccessControlService - so this exercises the actual scope logic, not
 * just that LeadService calls into it.
 */
@ExtendWith(MockitoExtension.class)
class LeadServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProductRepository productRepository;
    @Mock private IndustryRepository industryRepository;
    @Mock private LeadSourceMasterRepository leadSourceMasterRepository;
    @Mock private BatteryRepository batteryRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private ActivityLogService activityLogService;
    @Mock private OpportunityRepository opportunityRepository;
    @Mock private FollowUpRepository followUpRepository;
    @Mock private OpportunityService opportunityService;

    private LeadService leadService;

    private Role adminRole;
    private Role managerRole;
    private Role employeeRole;

    private Employee admin;
    private Employee managerA;
    private Employee managerB;
    private Employee reportOfA;
    private Employee reportOfB;

    private Lead leadOfReportOfA;
    private Lead leadOfManagerB;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        leadService = new LeadService(
                currentUserService,
                accessControlService,
                leadRepository,
                employeeRepository,
                productRepository,
                industryRepository,
                leadSourceMasterRepository,
                batteryRepository,
                activityLogService,
                opportunityRepository,
                followUpRepository,
                opportunityService
        );

        adminRole = Role.builder().id(1L).name("ADMIN").rank(100).build();
        managerRole = Role.builder().id(2L).name("MANAGER").rank(50).build();
        employeeRole = Role.builder().id(3L).name("EMPLOYEE").rank(10).build();

        admin = Employee.builder().id(1L).role(adminRole).build();
        managerA = Employee.builder().id(2L).role(managerRole).build();
        managerB = Employee.builder().id(3L).role(managerRole).build();
        reportOfA = Employee.builder().id(4L).role(employeeRole).manager(managerA).build();
        reportOfB = Employee.builder().id(5L).role(employeeRole).manager(managerB).build();

        leadOfReportOfA = Lead.builder().id(100L).assignedEmployee(reportOfA).build();
        leadOfManagerB = Lead.builder().id(101L).assignedEmployee(managerB).build();
    }

    private void grant(Role role, String permissionCode, Scope scope) {
        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.of(RolePermission.builder().scope(scope).build()));
    }

    // Explicit "no grant at all" stub - needed once a role already has ANY
    // stubbing on findByRole_IdAndPermission_Code, since Mockito's strict
    // stubbing (the MockitoExtension default) rejects an unstubbed
    // invocation with different arguments rather than silently falling
    // back to the Optional.empty() default.
    private void deny(Role role, String permissionCode) {
        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.empty());
    }

    // ---------- ADMIN ----------

    @Test
    void admin_canAccessLeadBelongingToAnyTeam() {

        grant(adminRole, LEAD_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));
        when(leadRepository.findById(101L)).thenReturn(Optional.of(leadOfManagerB));

        assertThat(leadService.getLeadById(100L)).isEqualTo(leadOfReportOfA);
        assertThat(leadService.getLeadById(101L)).isEqualTo(leadOfManagerB);
    }

    // ---------- MANAGER ----------

    @Test
    void manager_canAccessOwnAndDirectReportsLeads() {

        grant(managerRole, LEAD_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));

        assertThat(leadService.getLeadById(100L)).isEqualTo(leadOfReportOfA);
    }

    @Test
    void manager_cannotAccessAnotherManagersTeamLead() {

        grant(managerRole, LEAD_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(leadRepository.findById(101L)).thenReturn(Optional.of(leadOfManagerB));

        assertThatThrownBy(() -> leadService.getLeadById(101L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manager_cannotBypassTeamRestrictionByRequestingAnotherTeamsLeadId() {

        // Same check as above, framed as the explicit "change the id in the
        // request" scenario: manager A knows lead 101 belongs to manager
        // B's team and requests it directly by id.
        grant(managerRole, LEAD_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(leadRepository.findById(101L)).thenReturn(Optional.of(leadOfManagerB));

        assertThatThrownBy(() -> leadService.getLeadById(101L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manager_canReassignLeadToSomeoneWithinTheirOwnTeam() {

        grant(managerRole, LEAD_MANAGE, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));
        when(employeeRepository.findById(managerA.getId())).thenReturn(Optional.of(managerA));
        stubLeadUpdateLookups();
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));

        LeadRequest request = validLeadRequest(managerA.getId());

        Lead updated = leadService.updateLead(100L, request);

        assertThat(updated.getAssignedEmployee()).isEqualTo(managerA);
    }

    @Test
    void manager_cannotReassignLeadToSomeoneOutsideTheirTeam() {

        grant(managerRole, LEAD_MANAGE, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));
        stubLeadUpdateLookups();
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));

        // Attempt to reassign to reportOfB, who is outside managerA's team.
        LeadRequest request = validLeadRequest(reportOfB.getId());

        Lead updated = leadService.updateLead(100L, request);

        // Reassignment is silently skipped (not applied), same as today's
        // "only ADMIN can reassign" behavior for anyone lacking the grant -
        // the lead keeps its original owner rather than the request's.
        assertThat(updated.getAssignedEmployee()).isEqualTo(reportOfA);
    }

    // ---------- EMPLOYEE ----------

    @Test
    void employee_canAccessOwnLead() {

        grant(employeeRole, LEAD_VIEW, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));

        assertThat(leadService.getLeadById(100L)).isEqualTo(leadOfReportOfA);
    }

    @Test
    void employee_cannotAccessAnotherEmployeesLead() {

        Employee anotherReportOfA = Employee.builder().id(6L).role(employeeRole).manager(managerA).build();
        Lead leadOfAnotherReportOfA = Lead.builder().id(102L).assignedEmployee(anotherReportOfA).build();

        grant(employeeRole, LEAD_VIEW, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(leadRepository.findById(102L)).thenReturn(Optional.of(leadOfAnotherReportOfA));

        assertThatThrownBy(() -> leadService.getLeadById(102L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employee_cannotAccessAnotherManagersTeamLead() {

        grant(employeeRole, LEAD_VIEW, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(leadRepository.findById(101L)).thenReturn(Optional.of(leadOfManagerB));

        assertThatThrownBy(() -> leadService.getLeadById(101L))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- list scope filtering ----------
    // Exact Specification content isn't practical to assert via Mockito
    // (it's an opaque Criteria-API lambda) - these confirm searchLeads
    // wires resolveVisibleEmployeeIds into the query at all; real
    // filtering behavior against actual data is covered by
    // LeadSpecificationsIntegrationTest (a @DataJpaTest against H2).

    @Test
    void searchLeads_forManager_resolvesTeamScopeBeforeQuerying() {

        grant(managerRole, LEAD_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(leadRepository.findAll(org.mockito.ArgumentMatchers.nullable(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(leadOfReportOfA)));

        Page<Lead> result = leadService.searchLeads(new LeadSearchCriteria(), 0, 50, null, null);

        assertThat(result.getContent()).containsExactly(leadOfReportOfA);
        org.mockito.Mockito.verify(employeeRepository).findByManagerId(managerA.getId());
        org.mockito.Mockito.verify(leadRepository).findAll(
                org.mockito.ArgumentMatchers.nullable(Specification.class), any(Pageable.class));
    }

    @Test
    void searchLeads_forAdmin_neverResolvesTeamMembership() {

        grant(adminRole, LEAD_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(leadRepository.findAll(org.mockito.ArgumentMatchers.nullable(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(leadOfReportOfA, leadOfManagerB)));

        leadService.searchLeads(new LeadSearchCriteria(), 0, 50, null, null);

        // ALL scope never needs to know who reports to whom.
        org.mockito.Mockito.verify(employeeRepository, org.mockito.Mockito.never()).findByManagerId(any());
    }

    // ---------- delete authorization: ADMIN-only business rule (2026-08-19).
    // Deletion was split out of LEAD_MANAGE into its own admin-only
    // LEAD_DELETE permission - Manager (previously TEAM-scoped delete via
    // LEAD_MANAGE) and Employee must now be rejected outright, not merely
    // skipped per-record. ----------

    @Test
    void admin_canDeleteLead() {

        grant(adminRole, LEAD_DELETE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(leadRepository.findById(101L)).thenReturn(Optional.of(leadOfManagerB));

        leadService.deleteLead(101L);

        org.mockito.Mockito.verify(leadRepository).delete(leadOfManagerB);
        verify(activityLogService).log(
                eq(admin), eq(ActivityModule.LEAD), eq(ActivityAction.DELETE),
                eq(101L), any(), any());
    }

    @Test
    void manager_cannotDeleteLead_evenWithinOwnTeam() {

        // Manager still has LEAD_MANAGE (create/update/reassign) but no
        // grant at all for LEAD_DELETE.
        deny(managerRole, LEAD_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));

        assertThatThrownBy(() -> leadService.deleteLead(100L))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(leadRepository, org.mockito.Mockito.never()).delete(any(Lead.class));
    }

    @Test
    void employee_cannotDeleteOwnLead() {

        deny(employeeRole, LEAD_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));

        assertThatThrownBy(() -> leadService.deleteLead(100L))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(leadRepository, org.mockito.Mockito.never()).delete(any(Lead.class));
    }

    // ---------- bulk delete authorization ----------

    @Test
    void bulkDeleteLeads_admin_deletesAcrossTeams_skipsOnlyNotFound() {

        grant(adminRole, LEAD_DELETE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));
        when(leadRepository.findById(101L)).thenReturn(Optional.of(leadOfManagerB));
        when(leadRepository.findById(999L)).thenReturn(Optional.empty());

        com.compact.crm.dto.response.BulkOperationResult result =
                leadService.bulkDeleteLeads(List.of(100L, 101L, 999L));

        assertThat(result.getSucceededIds()).containsExactlyInAnyOrder(100L, 101L);
        assertThat(result.getSkippedIds()).containsExactly(999L);
        org.mockito.Mockito.verify(leadRepository).delete(leadOfReportOfA);
        org.mockito.Mockito.verify(leadRepository).delete(leadOfManagerB);
    }

    @Test
    void bulkDeleteLeads_manager_rejectedOutright_evenForOwnTeamRecords() {

        deny(managerRole, LEAD_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        assertThatThrownBy(() -> leadService.bulkDeleteLeads(List.of(100L)))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(leadRepository, org.mockito.Mockito.never()).delete(any(Lead.class));
    }

    @Test
    void bulkDeleteLeads_employee_rejectedOutright() {

        deny(employeeRole, LEAD_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);

        assertThatThrownBy(() -> leadService.bulkDeleteLeads(List.of(100L)))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(leadRepository, org.mockito.Mockito.never()).delete(any(Lead.class));
    }

    // ---------- export authorization: ADMIN-only, same split-permission
    // pattern as delete. ----------

    @Test
    void admin_canExportLeads() {

        grant(adminRole, LEAD_EXPORT, Scope.ALL);
        grant(adminRole, LEAD_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(leadRepository.findAll(org.mockito.ArgumentMatchers.nullable(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(leadOfReportOfA)));

        List<Lead> result = leadService.findForExport(new LeadSearchCriteria(), null, null, null);

        assertThat(result).containsExactly(leadOfReportOfA);
    }

    @Test
    void manager_cannotExportLeads() {

        deny(managerRole, LEAD_EXPORT);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        assertThatThrownBy(() -> leadService.findForExport(new LeadSearchCriteria(), null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employee_cannotExportLeads() {

        deny(employeeRole, LEAD_EXPORT);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);

        assertThatThrownBy(() -> leadService.findForExport(new LeadSearchCriteria(), null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- activity logging: the important actions actually get
    // logged, with the actor being whoever is currently authenticated. ----------

    @Test
    void createLead_logsLeadCreate() {

        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(employeeRepository.findById(reportOfA.getId())).thenReturn(Optional.of(reportOfA));
        stubLeadUpdateLookups();
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> {
            Lead saved = inv.getArgument(0);
            saved.setId(200L);
            return saved;
        });

        leadService.createLead(validLeadRequest(reportOfA.getId()));

        verify(activityLogService).log(
                eq(reportOfA), eq(ActivityModule.LEAD), eq(ActivityAction.CREATE),
                eq(200L), any(), any());
    }

    @Test
    void updateLead_logsLeadUpdate() {

        grant(employeeRole, LEAD_MANAGE, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(leadRepository.findById(100L)).thenReturn(Optional.of(leadOfReportOfA));
        stubLeadUpdateLookups();
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));

        leadService.updateLead(100L, validLeadRequest(reportOfA.getId()));

        verify(activityLogService).log(
                eq(reportOfA), eq(ActivityModule.LEAD), eq(ActivityAction.UPDATE),
                eq(100L), any(), any());
    }

    // deleteLead's logging is covered by admin_canDeleteLead above (delete
    // is now ADMIN-only - see the "delete authorization" section).

    // ---------- helpers ----------

    private void stubLeadUpdateLookups() {

        when(industryRepository.findById(any()))
                .thenReturn(Optional.of(com.compact.crm.entity.Industry.builder().id(1L).name("Manufacturing").build()));
        when(leadSourceMasterRepository.findById(any()))
                .thenReturn(Optional.of(com.compact.crm.entity.LeadSourceMaster.builder().id(1L).name("Referral").build()));
    }

    private LeadRequest validLeadRequest(Long assignedEmployeeId) {

        LeadRequest request = new LeadRequest();
        request.setCompanyName("Acme");
        request.setContactPerson("Jane Doe");
        request.setDesignation("Manager");
        request.setPhone("9876543210");
        request.setCity("Pune");
        request.setState("MH");
        request.setIndustryId(1L);
        request.setLeadSourceId(1L);
        request.setLeadStatus(LeadStatus.NEW);
        request.setAssignedEmployeeId(assignedEmployeeId);
        return request;
    }
}
