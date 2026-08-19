package com.compact.crm.service;

import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.FOLLOWUP_DELETE;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_EXPORT;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_MANAGE;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowUpServiceTest {

    @Mock private FollowUpRepository followUpRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private OpportunityRepository opportunityRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ActivityTypeRepository activityTypeRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private ActivityLogService activityLogService;

    private FollowUpService followUpService;

    private Role adminRole;
    private Role managerRole;
    private Role employeeRole;

    private Employee admin;
    private Employee managerA;
    private Employee managerB;
    private Employee reportOfA;

    private FollowUp followUpOfReportOfA;
    private FollowUp followUpOfManagerB;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        followUpService = new FollowUpService(
                followUpRepository,
                leadRepository,
                opportunityRepository,
                employeeRepository,
                activityTypeRepository,
                currentUserService,
                accessControlService,
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

        followUpOfReportOfA = FollowUp.builder().id(400L).lead(leadOfReportOfA).build();
        followUpOfManagerB = FollowUp.builder().id(401L).lead(leadOfManagerB).build();
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
    void admin_canAccessFollowUpFromAnyTeam() {

        grant(adminRole, FOLLOWUP_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(followUpRepository.findById(401L)).thenReturn(Optional.of(followUpOfManagerB));

        assertThat(followUpService.getFollowUpById(401L)).isEqualTo(followUpOfManagerB);
    }

    @Test
    void manager_canAccessDirectReportsFollowUp() {

        grant(managerRole, FOLLOWUP_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(followUpRepository.findById(400L)).thenReturn(Optional.of(followUpOfReportOfA));

        assertThat(followUpService.getFollowUpById(400L)).isEqualTo(followUpOfReportOfA);
    }

    @Test
    void manager_cannotAccessAnotherTeamsFollowUp_byRequestingItsIdDirectly() {

        grant(managerRole, FOLLOWUP_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(followUpRepository.findById(401L)).thenReturn(Optional.of(followUpOfManagerB));

        assertThatThrownBy(() -> followUpService.getFollowUpById(401L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employee_cannotAccessAnotherManagersTeamFollowUp() {

        grant(employeeRole, FOLLOWUP_VIEW, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(followUpRepository.findById(401L)).thenReturn(Optional.of(followUpOfManagerB));

        assertThatThrownBy(() -> followUpService.getFollowUpById(401L))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- delete/export authorization: ADMIN-only business rule
    // (2026-08-19). Split out of FOLLOWUP_MANAGE into its own admin-only
    // permission codes - Manager (previously TEAM-scoped delete via
    // FOLLOWUP_MANAGE) and Employee must now be rejected outright. ----------

    @Test
    void bulkDeleteFollowUps_admin_deletesAcrossTeams() {

        grant(adminRole, FOLLOWUP_DELETE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(followUpRepository.findById(400L)).thenReturn(Optional.of(followUpOfReportOfA));
        when(followUpRepository.findById(401L)).thenReturn(Optional.of(followUpOfManagerB));

        com.compact.crm.dto.response.BulkOperationResult result =
                followUpService.bulkDeleteFollowUps(List.of(400L, 401L));

        assertThat(result.getSucceededIds()).containsExactlyInAnyOrder(400L, 401L);
        org.mockito.Mockito.verify(followUpRepository).delete(followUpOfReportOfA);
        org.mockito.Mockito.verify(followUpRepository).delete(followUpOfManagerB);
    }

    @Test
    void bulkDeleteFollowUps_manager_rejectedOutright_evenForOwnTeamRecords() {

        deny(managerRole, FOLLOWUP_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        assertThatThrownBy(() -> followUpService.bulkDeleteFollowUps(List.of(400L)))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(followUpRepository, org.mockito.Mockito.never()).delete(any(FollowUp.class));
    }

    @Test
    void deleteFollowUp_employee_rejected() {

        deny(employeeRole, FOLLOWUP_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(followUpRepository.findById(400L)).thenReturn(Optional.of(followUpOfReportOfA));

        assertThatThrownBy(() -> followUpService.deleteFollowUp(400L))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(followUpRepository, org.mockito.Mockito.never()).delete(any(FollowUp.class));
    }

    @Test
    void admin_canExportFollowUps() {

        grant(adminRole, FOLLOWUP_EXPORT, Scope.ALL);
        grant(adminRole, FOLLOWUP_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(followUpRepository.findAll(
                org.mockito.ArgumentMatchers.nullable(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(followUpOfReportOfA)));

        List<FollowUp> result = followUpService.findForExport(
                new com.compact.crm.dto.search.FollowUpSearchCriteria(), null, null, null);

        assertThat(result).containsExactly(followUpOfReportOfA);
    }

    @Test
    void manager_cannotExportFollowUps() {

        deny(managerRole, FOLLOWUP_EXPORT);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        assertThatThrownBy(() -> followUpService.findForExport(
                new com.compact.crm.dto.search.FollowUpSearchCriteria(), null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- FOLLOWUP.COMPLETE vs FOLLOWUP.UPDATE: there's no separate
    // "complete" endpoint, so ActivityAction is chosen by diffing status
    // before/after inside updateFollowUp - this is the one hook point
    // worth testing directly since the logic isn't a simple 1:1 action
    // mapping like every other module's. ----------

    private FollowUpRequest requestFor(FollowUp followUp, FollowUpStatus newStatus) {

        FollowUpRequest request = new FollowUpRequest();
        request.setLeadId(followUp.getLead().getId());
        request.setEmployeeId(reportOfA.getId());
        request.setActivityTypeId(50L);
        request.setStatus(newStatus);
        request.setScheduledDate(LocalDateTime.now().plusDays(1));

        return request;
    }

    @Test
    void updateFollowUp_statusTransitionsToCompleted_logsCompleteNotUpdate() {

        followUpOfReportOfA.setStatus(FollowUpStatus.PENDING);

        grant(adminRole, FOLLOWUP_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(followUpRepository.findById(400L)).thenReturn(Optional.of(followUpOfReportOfA));
        when(leadRepository.findById(followUpOfReportOfA.getLead().getId()))
                .thenReturn(Optional.of(followUpOfReportOfA.getLead()));
        when(employeeRepository.findById(reportOfA.getId())).thenReturn(Optional.of(reportOfA));
        when(activityTypeRepository.findById(50L)).thenReturn(Optional.of(ActivityType.builder().id(50L).build()));
        when(followUpRepository.save(any(FollowUp.class))).thenAnswer(inv -> inv.getArgument(0));

        followUpService.updateFollowUp(400L, requestFor(followUpOfReportOfA, FollowUpStatus.COMPLETED));

        verify(activityLogService).log(
                eq(admin), eq(ActivityModule.FOLLOWUP), eq(ActivityAction.COMPLETE),
                eq(400L), any(), any());
    }

    @Test
    void updateFollowUp_statusUnchanged_logsUpdateNotComplete() {

        followUpOfReportOfA.setStatus(FollowUpStatus.PENDING);

        grant(adminRole, FOLLOWUP_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(followUpRepository.findById(400L)).thenReturn(Optional.of(followUpOfReportOfA));
        when(leadRepository.findById(followUpOfReportOfA.getLead().getId()))
                .thenReturn(Optional.of(followUpOfReportOfA.getLead()));
        when(employeeRepository.findById(reportOfA.getId())).thenReturn(Optional.of(reportOfA));
        when(activityTypeRepository.findById(50L)).thenReturn(Optional.of(ActivityType.builder().id(50L).build()));
        when(followUpRepository.save(any(FollowUp.class))).thenAnswer(inv -> inv.getArgument(0));

        followUpService.updateFollowUp(400L, requestFor(followUpOfReportOfA, FollowUpStatus.PENDING));

        verify(activityLogService).log(
                eq(admin), eq(ActivityModule.FOLLOWUP), eq(ActivityAction.UPDATE),
                eq(400L), any(), any());
    }
}
