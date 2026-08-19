package com.compact.crm.security;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RolePermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Covers the core scope-resolution and team-membership logic that every
 * service (Lead/Opportunity/Customer/FollowUp) delegates to, independent of
 * any one module - see the RBAC scenarios in the task's section 11.
 */
@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    private AccessControlService accessControlService;

    private Role adminRole;
    private Role managerRoleA;
    private Role managerRoleB;
    private Role employeeRole;

    private Employee admin;
    private Employee managerA;
    private Employee managerB;
    private Employee reportOfA1;
    private Employee reportOfA2;
    private Employee reportOfB1;

    private static final String LEAD_VIEW = AccessControlService.LEAD_VIEW;

    @BeforeEach
    void setUp() {

        accessControlService = new AccessControlService(rolePermissionRepository, employeeRepository);

        adminRole = Role.builder().id(1L).name("ADMIN").rank(100).build();
        managerRoleA = Role.builder().id(2L).name("MANAGER").rank(50).build();
        managerRoleB = managerRoleA;
        employeeRole = Role.builder().id(3L).name("EMPLOYEE").rank(10).build();

        admin = Employee.builder().id(1L).role(adminRole).build();
        managerA = Employee.builder().id(2L).role(managerRoleA).build();
        managerB = Employee.builder().id(3L).role(managerRoleB).build();
        reportOfA1 = Employee.builder().id(4L).role(employeeRole).manager(managerA).build();
        reportOfA2 = Employee.builder().id(5L).role(employeeRole).manager(managerA).build();
        reportOfB1 = Employee.builder().id(6L).role(employeeRole).manager(managerB).build();
    }

    private void grant(Role role, String permissionCode, Scope scope) {

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.of(RolePermission.builder().scope(scope).build()));
    }

    // ---------- resolveScope / hasPermission ----------

    @Test
    void resolveScope_returnsNull_whenRoleHasNoGrantForPermission() {

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(employeeRole.getId(), LEAD_VIEW))
                .thenReturn(Optional.empty());

        assertThat(accessControlService.resolveScope(reportOfA1, LEAD_VIEW)).isNull();
        assertThat(accessControlService.hasPermission(reportOfA1, LEAD_VIEW)).isFalse();
    }

    // ---------- teamMemberIds ----------

    @Test
    void teamMemberIds_includesSelfAndDirectReportsOnly() {

        when(employeeRepository.findByManagerId(managerA.getId()))
                .thenReturn(List.of(reportOfA1, reportOfA2));

        List<Long> team = accessControlService.teamMemberIds(managerA);

        assertThat(team).containsExactlyInAnyOrder(managerA.getId(), reportOfA1.getId(), reportOfA2.getId());
        assertThat(team).doesNotContain(managerB.getId(), reportOfB1.getId());
    }

    @Test
    void teamMemberIds_forEmployeeWithNoReports_isJustSelf() {

        when(employeeRepository.findByManagerId(reportOfA1.getId())).thenReturn(List.of());

        assertThat(accessControlService.teamMemberIds(reportOfA1)).containsExactly(reportOfA1.getId());
    }

    // ---------- assertCanAccess: ADMIN ----------

    @Test
    void admin_canAccessAnyRecord_regardlessOfOwningTeam() {

        grant(adminRole, LEAD_VIEW, Scope.ALL);

        accessControlService.assertCanAccess(admin, LEAD_VIEW, reportOfA1.getId());
        accessControlService.assertCanAccess(admin, LEAD_VIEW, reportOfB1.getId());
        accessControlService.assertCanAccess(admin, LEAD_VIEW, managerB.getId());
        // no exception thrown for any of the above = pass
    }

    // ---------- assertCanAccess: MANAGER (TEAM scope) ----------

    @Test
    void manager_canAccessOwnRecordAndDirectReportsRecords() {

        grant(managerRoleA, LEAD_VIEW, Scope.TEAM);
        when(employeeRepository.findByManagerId(managerA.getId()))
                .thenReturn(List.of(reportOfA1, reportOfA2));

        accessControlService.assertCanAccess(managerA, LEAD_VIEW, managerA.getId());
        accessControlService.assertCanAccess(managerA, LEAD_VIEW, reportOfA1.getId());
        accessControlService.assertCanAccess(managerA, LEAD_VIEW, reportOfA2.getId());
    }

    @Test
    void manager_cannotAccessAnotherManagersTeamRecord() {

        grant(managerRoleA, LEAD_VIEW, Scope.TEAM);
        when(employeeRepository.findByManagerId(managerA.getId()))
                .thenReturn(List.of(reportOfA1, reportOfA2));

        assertThatThrownBy(() ->
                accessControlService.assertCanAccess(managerA, LEAD_VIEW, reportOfB1.getId())
        ).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manager_cannotBypassTeamRestrictionByChangingTheRecordId() {

        grant(managerRoleA, LEAD_VIEW, Scope.TEAM);
        when(employeeRepository.findByManagerId(managerA.getId()))
                .thenReturn(List.of(reportOfA1, reportOfA2));

        // Simulates a manager editing the URL/path id to try to reach a
        // record owned by another manager's team.
        assertThatThrownBy(() ->
                accessControlService.assertCanAccess(managerA, LEAD_VIEW, managerB.getId())
        ).isInstanceOf(AccessDeniedException.class);
    }

    // ---------- assertCanAccess: EMPLOYEE (OWN scope) ----------

    @Test
    void employee_canAccessOwnRecord() {

        grant(employeeRole, LEAD_VIEW, Scope.OWN);

        accessControlService.assertCanAccess(reportOfA1, LEAD_VIEW, reportOfA1.getId());
    }

    @Test
    void employee_cannotAccessAnotherEmployeesRecord() {

        grant(employeeRole, LEAD_VIEW, Scope.OWN);

        assertThatThrownBy(() ->
                accessControlService.assertCanAccess(reportOfA1, LEAD_VIEW, reportOfA2.getId())
        ).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employee_cannotAccessAnotherManagersTeamRecord() {

        grant(employeeRole, LEAD_VIEW, Scope.OWN);

        assertThatThrownBy(() ->
                accessControlService.assertCanAccess(reportOfA1, LEAD_VIEW, reportOfB1.getId())
        ).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noGrantAtAll_denies_regardlessOfOwner() {

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(employeeRole.getId(), LEAD_VIEW))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                accessControlService.assertCanAccess(reportOfA1, LEAD_VIEW, reportOfA1.getId())
        ).isInstanceOf(AccessDeniedException.class);
    }

    // ---------- resolveVisibleEmployeeIds (drives list-query scope filtering) ----------

    @Test
    void resolveVisibleEmployeeIds_all_returnsNullMeaningNoFilter() {

        grant(adminRole, LEAD_VIEW, Scope.ALL);

        assertThat(accessControlService.resolveVisibleEmployeeIds(admin, LEAD_VIEW)).isNull();
    }

    @Test
    void resolveVisibleEmployeeIds_team_returnsSelfPlusDirectReports() {

        grant(managerRoleA, LEAD_VIEW, Scope.TEAM);
        when(employeeRepository.findByManagerId(managerA.getId()))
                .thenReturn(List.of(reportOfA1, reportOfA2));

        assertThat(accessControlService.resolveVisibleEmployeeIds(managerA, LEAD_VIEW))
                .containsExactlyInAnyOrder(managerA.getId(), reportOfA1.getId(), reportOfA2.getId());
    }

    @Test
    void resolveVisibleEmployeeIds_own_returnsJustSelf() {

        grant(employeeRole, LEAD_VIEW, Scope.OWN);

        assertThat(accessControlService.resolveVisibleEmployeeIds(reportOfA1, LEAD_VIEW))
                .containsExactly(reportOfA1.getId());
    }

    // ---------- requirePermission (hard gate for actions with no
    // per-record ownership dimension, e.g. bulk delete / export) ----------

    @Test
    void requirePermission_passes_whenRoleHasAnyGrant() {

        grant(adminRole, LEAD_VIEW, Scope.ALL);

        accessControlService.requirePermission(admin, LEAD_VIEW);
        // no exception thrown = pass
    }

    @Test
    void requirePermission_throws_whenRoleHasNoGrantAtAll() {

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(employeeRole.getId(), LEAD_VIEW))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessControlService.requirePermission(reportOfA1, LEAD_VIEW))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- canAssignTo (reassignment target validation) ----------

    @Test
    void canAssignTo_all_allowsAnyTarget() {

        grant(adminRole, LEAD_VIEW, Scope.ALL);

        assertThat(accessControlService.canAssignTo(admin, LEAD_VIEW, reportOfB1.getId())).isTrue();
    }

    @Test
    void canAssignTo_team_allowsOnlyWithinOwnTeam() {

        grant(managerRoleA, LEAD_VIEW, Scope.TEAM);
        when(employeeRepository.findByManagerId(managerA.getId()))
                .thenReturn(List.of(reportOfA1, reportOfA2));

        assertThat(accessControlService.canAssignTo(managerA, LEAD_VIEW, reportOfA1.getId())).isTrue();
        assertThat(accessControlService.canAssignTo(managerA, LEAD_VIEW, reportOfB1.getId())).isFalse();
    }

    @Test
    void canAssignTo_own_neverAllowsReassignment() {

        grant(employeeRole, LEAD_VIEW, Scope.OWN);

        assertThat(accessControlService.canAssignTo(reportOfA1, LEAD_VIEW, reportOfA1.getId())).isFalse();
    }
}
