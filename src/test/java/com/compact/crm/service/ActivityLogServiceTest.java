package com.compact.crm.service;

import com.compact.crm.dto.response.ActivitySummaryResponse;
import com.compact.crm.dto.search.ActivityLogSearchCriteria;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;

import static com.compact.crm.security.AccessControlService.ACTIVITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Real-database coverage of ActivityLogService's read side (RBAC scoping,
 * filters, pagination, summary aggregation) - same rationale as
 * LeadSpecificationsIntegrationTest: only a real query against real rows
 * can confirm the generated Criteria-API predicates actually filter
 * correctly, especially the RBAC actorIn/hasEmployeeId intersection that
 * stops a Manager/Employee from viewing outside their scope. The write
 * path's "never break the caller" safety net is covered separately in
 * ActivityLogLoggingSafetyTest (pure Mockito, no DB needed for that).
 */
@DataJpaTest
@ExtendWith(MockitoExtension.class)
class ActivityLogServiceTest {

    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;

    @Mock private CurrentUserService currentUserService;

    private ActivityLogService activityLogService;

    private Employee admin;
    private Employee managerA;
    private Employee reportOfA;
    private Employee managerB;
    private Employee reportOfB;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        activityLogService = new ActivityLogService(
                activityLogRepository, currentUserService, accessControlService);

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        // Same grants V4 seeds in production: ADMIN=ALL, MANAGER=TEAM,
        // EMPLOYEE=OWN for ACTIVITY_VIEW. Flyway doesn't run in tests (H2
        // builds schema from entities), so this data has to be seeded here
        // by hand, same as every other RBAC-dependent test in this suite.
        Permission activityView = permissionRepository.save(Permission.builder().code(ACTIVITY_VIEW).build());

        rolePermissionRepository.save(RolePermission.builder().role(adminRole).permission(activityView).scope(Scope.ALL).build());
        rolePermissionRepository.save(RolePermission.builder().role(managerRole).permission(activityView).scope(Scope.TEAM).build());
        rolePermissionRepository.save(RolePermission.builder().role(employeeRole).permission(activityView).scope(Scope.OWN).build());

        admin = employeeRepository.save(Employee.builder()
                .name("Admin").email("admin@example.com").phone("9000000001").password("x").role(adminRole).build());

        managerA = employeeRepository.save(Employee.builder()
                .name("Manager A").email("managera@example.com").phone("9000000002").password("x").role(managerRole).build());

        reportOfA = employeeRepository.save(Employee.builder()
                .name("Report A1").email("reporta1@example.com").phone("9000000003").password("x")
                .role(employeeRole).manager(managerA).build());

        managerB = employeeRepository.save(Employee.builder()
                .name("Manager B").email("managerb@example.com").phone("9000000004").password("x").role(managerRole).build());

        reportOfB = employeeRepository.save(Employee.builder()
                .name("Report B1").email("reportb1@example.com").phone("9000000005").password("x")
                .role(employeeRole).manager(managerB).build());
    }

    private ActivityLog seed(Employee actor, ActivityModule module, ActivityAction action, LocalDateTime createdAt) {

        return activityLogRepository.save(ActivityLog.builder()
                .employee(actor)
                .employeeName(actor.getName())
                .module(module)
                .action(action)
                .createdAt(createdAt)
                .build());
    }

    private ActivityLogSearchCriteria emptyCriteria() {
        return new ActivityLogSearchCriteria();
    }

    // ---------- write path: actor is recorded exactly as passed ----------

    @Test
    void log_persistsEntryWithGivenActorAndFields() {

        activityLogService.log(
                reportOfA, ActivityModule.LEAD, ActivityAction.CREATE,
                104L, "ABC Industries", "Created lead");

        Page<ActivityLog> all = activityLogRepository.findAll(org.springframework.data.domain.Pageable.unpaged());

        assertThat(all.getContent()).hasSize(1);
        ActivityLog entry = all.getContent().get(0);

        assertThat(entry.getEmployeeId()).isEqualTo(reportOfA.getId());
        assertThat(entry.getEmployeeName()).isEqualTo("Report A1");
        assertThat(entry.getModule()).isEqualTo(ActivityModule.LEAD);
        assertThat(entry.getAction()).isEqualTo(ActivityAction.CREATE);
        assertThat(entry.getEntityId()).isEqualTo(104L);
        assertThat(entry.getEntityName()).isEqualTo("ABC Industries");
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    // ---------- RBAC scoping ----------

    @Test
    void search_admin_seesActivityFromEveryEmployee() {

        seed(managerA, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now());
        seed(reportOfB, ActivityModule.LEAD, ActivityAction.UPDATE, LocalDateTime.now());

        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        Page<ActivityLog> result = activityLogService.search(emptyCriteria(), 0, 50, null, null);

        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    void search_manager_seesOwnAndDirectReportsOnly_notAnotherTeam() {

        seed(managerA, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now());
        seed(reportOfA, ActivityModule.LEAD, ActivityAction.UPDATE, LocalDateTime.now());
        seed(managerB, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now());
        seed(reportOfB, ActivityModule.LEAD, ActivityAction.UPDATE, LocalDateTime.now());

        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        Page<ActivityLog> result = activityLogService.search(emptyCriteria(), 0, 50, null, null);

        assertThat(result.getContent())
                .extracting(ActivityLog::getEmployeeId)
                .containsExactlyInAnyOrder(managerA.getId(), reportOfA.getId());
    }

    @Test
    void search_employee_seesOwnActivityOnly() {

        seed(reportOfA, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now());
        seed(managerA, ActivityModule.LEAD, ActivityAction.UPDATE, LocalDateTime.now());
        seed(reportOfB, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now());

        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);

        Page<ActivityLog> result = activityLogService.search(emptyCriteria(), 0, 50, null, null);

        assertThat(result.getContent())
                .extracting(ActivityLog::getEmployeeId)
                .containsExactly(reportOfA.getId());
    }

    @Test
    void search_manager_cannotViewAnotherManagersTeam_viaEmployeeIdFilter() {

        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        ActivityLogSearchCriteria criteria = emptyCriteria();
        criteria.setEmployeeId(managerB.getId());

        assertThatThrownBy(() -> activityLogService.search(criteria, 0, 50, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void search_employee_cannotFakeAnotherEmployeesActivity_viaEmployeeIdFilter() {

        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);

        ActivityLogSearchCriteria criteria = emptyCriteria();
        criteria.setEmployeeId(managerA.getId());

        assertThatThrownBy(() -> activityLogService.search(criteria, 0, 50, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void search_manager_employeeIdFilter_withinTeam_narrowsToThatEmployeeOnly() {

        seed(managerA, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now());
        seed(reportOfA, ActivityModule.LEAD, ActivityAction.UPDATE, LocalDateTime.now());

        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        ActivityLogSearchCriteria criteria = emptyCriteria();
        criteria.setEmployeeId(reportOfA.getId());

        Page<ActivityLog> result = activityLogService.search(criteria, 0, 50, null, null);

        assertThat(result.getContent())
                .extracting(ActivityLog::getEmployeeId)
                .containsExactly(reportOfA.getId());
    }

    // ---------- filters ----------

    @Test
    void search_filtersByModuleAndAction() {

        seed(admin, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now());
        seed(admin, ActivityModule.LEAD, ActivityAction.UPDATE, LocalDateTime.now());
        seed(admin, ActivityModule.FOLLOWUP, ActivityAction.COMPLETE, LocalDateTime.now());

        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        ActivityLogSearchCriteria criteria = emptyCriteria();
        criteria.setModule(ActivityModule.LEAD);
        criteria.setAction(ActivityAction.UPDATE);

        Page<ActivityLog> result = activityLogService.search(criteria, 0, 50, null, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getAction()).isEqualTo(ActivityAction.UPDATE);
    }

    @Test
    void search_filtersByDateRange() {

        seed(admin, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.of(2026, 1, 1, 10, 0));
        seed(admin, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.of(2026, 6, 1, 10, 0));

        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        ActivityLogSearchCriteria criteria = emptyCriteria();
        criteria.setCreatedFrom(java.time.LocalDate.of(2026, 5, 1));
        criteria.setCreatedTo(java.time.LocalDate.of(2026, 6, 30));

        Page<ActivityLog> result = activityLogService.search(criteria, 0, 50, null, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCreatedAt().getMonthValue()).isEqualTo(6);
    }

    // ---------- pagination ----------

    @Test
    void search_pagination_respectsPageAndSize() {

        for (int i = 0; i < 5; i++) {
            seed(admin, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now().minusMinutes(i));
        }

        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        Page<ActivityLog> firstPage = activityLogService.search(emptyCriteria(), 0, 2, null, null);

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
    }

    // ---------- summary ----------

    @Test
    void getSummary_countsMatchExpectedBuckets() {

        seed(admin, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now().minusHours(3));
        seed(admin, ActivityModule.LEAD, ActivityAction.CREATE, LocalDateTime.now().minusHours(2));
        seed(admin, ActivityModule.LEAD, ActivityAction.UPDATE, LocalDateTime.now().minusHours(1));
        seed(admin, ActivityModule.FOLLOWUP, ActivityAction.COMPLETE, LocalDateTime.now().minusMinutes(30));
        seed(admin, ActivityModule.LEAD, ActivityAction.CONVERT, LocalDateTime.now().minusMinutes(20));
        seed(admin, ActivityModule.OPPORTUNITY, ActivityAction.CONVERT, LocalDateTime.now().minusMinutes(10));

        LocalDateTime loginAt = LocalDateTime.now().minusMinutes(5);
        seed(admin, ActivityModule.AUTH, ActivityAction.LOGIN, loginAt);

        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        ActivitySummaryResponse summary = activityLogService.getSummary(emptyCriteria());

        assertThat(summary.getTotalActions()).isEqualTo(7);
        assertThat(summary.getLeadsCreated()).isEqualTo(2);
        assertThat(summary.getLeadsUpdated()).isEqualTo(1);
        assertThat(summary.getFollowUpsCompleted()).isEqualTo(1);
        assertThat(summary.getConversions()).isEqualTo(2);
        assertThat(summary.getLastLoginAt()).isEqualTo(loginAt);
        assertThat(summary.getLastActiveAt()).isEqualTo(loginAt);
    }
}
