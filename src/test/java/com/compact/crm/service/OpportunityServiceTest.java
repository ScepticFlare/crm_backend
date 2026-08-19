package com.compact.crm.service;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.Scope;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.OPPORTUNITY_DELETE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_EXPORT;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpportunityServiceTest {

    @Mock private OpportunityRepository opportunityRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private FollowUpRepository followUpRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private SalesStageService salesStageService;
    @Mock private ActivityLogService activityLogService;

    private OpportunityService opportunityService;

    private Role adminRole;
    private Role managerRole;
    private Role employeeRole;

    private Employee admin;
    private Employee managerA;
    private Employee managerB;
    private Employee reportOfA;

    private Opportunity opportunityOfReportOfA;
    private Opportunity opportunityOfManagerB;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        opportunityService = new OpportunityService(
                opportunityRepository,
                leadRepository,
                customerRepository,
                followUpRepository,
                currentUserService,
                accessControlService,
                salesStageService,
                activityLogService
        );

        adminRole = Role.builder().id(1L).name("ADMIN").rank(100).build();
        managerRole = Role.builder().id(2L).name("MANAGER").rank(50).build();
        employeeRole = Role.builder().id(3L).name("EMPLOYEE").rank(10).build();

        admin = Employee.builder().id(1L).role(adminRole).build();
        managerA = Employee.builder().id(2L).role(managerRole).build();
        managerB = Employee.builder().id(3L).role(managerRole).build();
        reportOfA = Employee.builder().id(4L).role(employeeRole).manager(managerA).build();

        Lead leadOfReportOfA = Lead.builder().id(10L).assignedEmployee(reportOfA).build();
        Lead leadOfManagerB = Lead.builder().id(11L).assignedEmployee(managerB).build();

        opportunityOfReportOfA = Opportunity.builder().id(200L).lead(leadOfReportOfA).build();
        opportunityOfManagerB = Opportunity.builder().id(201L).lead(leadOfManagerB).build();
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

    @Test
    void admin_canAccessOpportunityFromAnyTeam() {

        grant(adminRole, OPPORTUNITY_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(opportunityRepository.findById(201L)).thenReturn(Optional.of(opportunityOfManagerB));

        assertThat(opportunityService.getOpportunityById(201L)).isEqualTo(opportunityOfManagerB);
    }

    @Test
    void manager_canAccessDirectReportsOpportunity() {

        grant(managerRole, OPPORTUNITY_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(opportunityRepository.findById(200L)).thenReturn(Optional.of(opportunityOfReportOfA));

        assertThat(opportunityService.getOpportunityById(200L)).isEqualTo(opportunityOfReportOfA);
    }

    @Test
    void manager_cannotAccessAnotherTeamsOpportunity_evenByRequestingItsIdDirectly() {

        grant(managerRole, OPPORTUNITY_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(opportunityRepository.findById(201L)).thenReturn(Optional.of(opportunityOfManagerB));

        assertThatThrownBy(() -> opportunityService.getOpportunityById(201L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employee_cannotAccessAnotherTeamsOpportunity() {

        grant(employeeRole, OPPORTUNITY_VIEW, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(opportunityRepository.findById(201L)).thenReturn(Optional.of(opportunityOfManagerB));

        assertThatThrownBy(() -> opportunityService.getOpportunityById(201L))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- delete/export authorization: ADMIN-only business rule
    // (2026-08-19). Split out of OPPORTUNITY_MANAGE into its own admin-only
    // permission codes - Manager (previously TEAM-scoped delete via
    // OPPORTUNITY_MANAGE) and Employee must now be rejected outright. ----------

    @Test
    void bulkDeleteOpportunities_admin_deletesAcrossTeams() {

        opportunityOfReportOfA.setSalesStage(
                com.compact.crm.entity.SalesStage.builder().id(1L).name("NEW").build());
        opportunityOfManagerB.setSalesStage(
                com.compact.crm.entity.SalesStage.builder().id(1L).name("NEW").build());

        grant(adminRole, OPPORTUNITY_DELETE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(opportunityRepository.findById(200L)).thenReturn(Optional.of(opportunityOfReportOfA));
        when(opportunityRepository.findById(201L)).thenReturn(Optional.of(opportunityOfManagerB));

        com.compact.crm.dto.response.BulkOperationResult result =
                opportunityService.bulkDeleteOpportunities(List.of(200L, 201L));

        assertThat(result.getSucceededIds()).containsExactlyInAnyOrder(200L, 201L);
        org.mockito.Mockito.verify(opportunityRepository).delete(opportunityOfReportOfA);
        org.mockito.Mockito.verify(opportunityRepository).delete(opportunityOfManagerB);
    }

    @Test
    void bulkDeleteOpportunities_manager_rejectedOutright_evenForOwnTeamRecords() {

        deny(managerRole, OPPORTUNITY_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        assertThatThrownBy(() -> opportunityService.bulkDeleteOpportunities(List.of(200L)))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(opportunityRepository, org.mockito.Mockito.never())
                .delete(any(Opportunity.class));
    }

    @Test
    void deleteOpportunity_employee_rejected() {

        deny(employeeRole, OPPORTUNITY_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(opportunityRepository.findById(200L)).thenReturn(Optional.of(opportunityOfReportOfA));

        assertThatThrownBy(() -> opportunityService.deleteOpportunity(200L))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(opportunityRepository, org.mockito.Mockito.never())
                .delete(any(Opportunity.class));
    }

    @Test
    void admin_canExportOpportunities() {

        grant(adminRole, OPPORTUNITY_EXPORT, Scope.ALL);
        grant(adminRole, OPPORTUNITY_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(opportunityRepository.findAll(
                org.mockito.ArgumentMatchers.nullable(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(opportunityOfReportOfA)));

        List<Opportunity> result = opportunityService.findForExport(
                new com.compact.crm.dto.search.OpportunitySearchCriteria(), null, null, null);

        assertThat(result).containsExactly(opportunityOfReportOfA);
    }

    @Test
    void manager_cannotExportOpportunities() {

        deny(managerRole, OPPORTUNITY_EXPORT);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        assertThatThrownBy(() -> opportunityService.findForExport(
                new com.compact.crm.dto.search.OpportunitySearchCriteria(), null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
