package com.compact.crm.service;

import com.compact.crm.dto.request.EmployeeRequest;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import java.util.List;

import static com.compact.crm.security.AccessControlService.EMPLOYEE_MANAGE;
import static com.compact.crm.security.AccessControlService.EMPLOYEE_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the privilege-escalation fix made while wiring EMPLOYEE_MANAGE:
 * previously any employee could PUT their own /api/employees/{id} record
 * and set their own role field, since the only check was "admin, or
 * editing yourself". Role/manager changes now require EMPLOYEE_MANAGE
 * regardless of whose record is being edited.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CurrentUserService currentUserService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private FollowUpRepository followUpRepository;
    @Mock private ActivityLogService activityLogService;

    private EmployeeService employeeService;

    private Role adminRole;
    private Role employeeRole;
    private Employee admin;
    private Employee plainEmployee;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        employeeService = new EmployeeService(
                employeeRepository,
                roleRepository,
                passwordEncoder,
                currentUserService,
                accessControlService,
                leadRepository,
                customerRepository,
                followUpRepository,
                activityLogService
        );

        adminRole = Role.builder().id(1L).name("ADMIN").rank(100).build();
        employeeRole = Role.builder().id(2L).name("EMPLOYEE").rank(10).build();

        admin = Employee.builder().id(1L).name("Admin").role(adminRole).build();
        plainEmployee = Employee.builder().id(2L).name("Priya").role(employeeRole).build();
    }

    private void grant(Role role, String permissionCode, Scope scope) {
        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.of(RolePermission.builder().scope(scope).build()));
    }

    @Test
    void employee_editingOwnProfile_cannotChangeOwnRole() {

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(employeeRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(plainEmployee);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya Updated");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("ADMIN"); // attempted self-promotion

        Employee updated = employeeService.updateEmployee(2L, request);

        assertThat(updated.getName()).isEqualTo("Priya Updated");
        // Role must be unchanged - still the original EMPLOYEE role entity,
        // never looked up/set from the request.
        assertThat(updated.getRole()).isEqualTo(employeeRole);
    }

    @Test
    void admin_canChangeAnotherEmployeesRoleAndManager() {

        Role managerRole = Role.builder().id(4L).name("MANAGER").rank(50).build();
        Employee newManager = Employee.builder().id(3L).name("Manager A").role(managerRole).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        when(employeeRepository.findById(3L)).thenReturn(Optional.of(newManager));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");
        request.setManagerId(3L);

        Employee updated = employeeService.updateEmployee(2L, request);

        assertThat(updated.getRole()).isEqualTo(employeeRole);
        assertThat(updated.getManager()).isEqualTo(newManager);
    }

    @Test
    void nonAdminEmployee_cannotEditAnotherEmployeesProfileAtAll() {

        Employee anotherEmployee = Employee.builder().id(5L).name("Other").role(employeeRole).build();

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(employeeRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(plainEmployee);
        when(employeeRepository.findById(5L)).thenReturn(Optional.of(anotherEmployee));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Hacked");
        request.setEmail("x@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");

        assertThatThrownBy(() -> employeeService.updateEmployee(5L, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- getAllEmployees (EMPLOYEE_VIEW) - backs the "Assigned
    // Employee" picker added to the frontend ----------

    @Test
    void admin_listingEmployees_seesEveryone() {

        grant(adminRole, EMPLOYEE_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findAll()).thenReturn(List.of(admin, plainEmployee));

        assertThat(employeeService.getAllEmployees()).containsExactly(admin, plainEmployee);
    }

    @Test
    void manager_listingEmployees_seesOnlyOwnTeam() {

        Role managerRole = Role.builder().id(4L).name("MANAGER").rank(50).build();
        Employee managerA = Employee.builder().id(10L).name("Manager A").role(managerRole).build();
        Employee reportOfA = Employee.builder().id(11L).name("Report A1").role(employeeRole).manager(managerA).build();

        grant(managerRole, EMPLOYEE_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(employeeRepository.findAllById(List.of(managerA.getId(), reportOfA.getId())))
                .thenReturn(List.of(managerA, reportOfA));

        List<Employee> team = employeeService.getAllEmployees();

        assertThat(team).containsExactlyInAnyOrder(managerA, reportOfA);
        assertThat(team).doesNotContain(admin);
    }

    @Test
    void plainEmployee_listingEmployees_isStillRejected() {

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(employeeRole.getId(), EMPLOYEE_VIEW))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(plainEmployee);

        assertThatThrownBy(() -> employeeService.getAllEmployees())
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- MANAGER-specific restrictions (Section 11 of the UX/security
    // cleanup pass: a Manager must never be able to create/delete employees,
    // change roles, change manager assignments, or view an employee outside
    // their own team - even though they hold EMPLOYEE_VIEW=TEAM). Manager
    // seed data (V2/V3 migrations) grants EMPLOYEE_VIEW but never
    // EMPLOYEE_MANAGE, so these assert that boundary holds at the service
    // layer regardless of future seed-data changes. ----------

    private Role managerRole() {
        return Role.builder().id(4L).name("MANAGER").rank(50).build();
    }

    @Test
    void manager_cannotCreateEmployee() {

        Role managerRole = managerRole();
        Employee manager = Employee.builder().id(10L).name("Manager A").role(managerRole).build();

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(managerRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(manager);

        EmployeeRequest request = new EmployeeRequest();
        request.setName("New Hire");
        request.setEmail("newhire@example.com");
        request.setPhone("9876543210");
        request.setPassword("password123");
        request.setRole("EMPLOYEE");

        assertThatThrownBy(() -> employeeService.createEmployee(request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manager_cannotDeleteEmployee() {

        Role managerRole = managerRole();
        Employee manager = Employee.builder().id(10L).name("Manager A").role(managerRole).build();

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(managerRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(manager);

        assertThatThrownBy(() -> employeeService.deleteEmployee(11L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manager_cannotChangeTeamMembersRoleOrManager() {

        Role managerRole = managerRole();
        Employee manager = Employee.builder().id(10L).name("Manager A").role(managerRole).build();
        Employee report = Employee.builder().id(11L).name("Report A1").role(employeeRole).manager(manager).build();

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(managerRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(manager);
        when(employeeRepository.findById(11L)).thenReturn(Optional.of(report));

        // A manager is not "self" when editing a direct report, so this must
        // 403 outright rather than silently drop the role/manager change.
        EmployeeRequest request = new EmployeeRequest();
        request.setName("Report A1");
        request.setEmail("report1@example.com");
        request.setPhone("9876543210");
        request.setRole("ADMIN");

        assertThatThrownBy(() -> employeeService.updateEmployee(11L, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manager_editingOwnProfile_cannotChangeOwnRole() {

        Role managerRole = managerRole();
        Employee manager = Employee.builder().id(10L).name("Manager A").role(managerRole).build();

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(managerRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(manager);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Manager A");
        request.setEmail("managera@example.com");
        request.setPhone("9876543210");
        request.setRole("ADMIN"); // attempted self-promotion

        Employee updated = employeeService.updateEmployee(10L, request);

        assertThat(updated.getRole()).isEqualTo(managerRole);
    }

    @Test
    void manager_cannotViewEmployeeOutsideTeam_viaGetById() {

        Role managerRole = managerRole();
        Employee manager = Employee.builder().id(10L).name("Manager A").role(managerRole).build();
        Employee outsider = Employee.builder().id(99L).name("Other Team").role(employeeRole).build();

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(managerRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(manager);
        when(employeeRepository.findById(99L)).thenReturn(Optional.of(outsider));

        assertThatThrownBy(() -> employeeService.getEmployeeById(99L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manager_canViewOwnProfile_viaGetById() {

        Role managerRole = managerRole();
        Employee manager = Employee.builder().id(10L).name("Manager A").role(managerRole).build();

        when(currentUserService.getCurrentEmployee()).thenReturn(manager);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(manager));

        assertThat(employeeService.getEmployeeById(10L)).isEqualTo(manager);
    }

    // ---------- Hierarchy integrity: managerId must point to a MANAGER,
    // never to a self-reference, and a Manager with direct reports cannot
    // be demoted (or deleted) out from under them. Reproduces and closes
    // the bug found in local testing: Employee -> Manager -> (gains a
    // report) -> Employee again, leaving the report's managerId pointing
    // at a non-Manager. ----------

    @Test
    void employee_cannotCreateManager() {

        Role managerRole = managerRole();

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(employeeRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(plainEmployee);

        EmployeeRequest request = new EmployeeRequest();
        request.setName("New Manager");
        request.setEmail("newmanager@example.com");
        request.setPhone("9876543211");
        request.setPassword("password123");
        request.setRole(managerRole.getName());

        assertThatThrownBy(() -> employeeService.createEmployee(request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employee_cannotManipulateManagerIdDirectly_onOwnProfile() {

        when(rolePermissionRepository.findByRole_IdAndPermission_Code(employeeRole.getId(), EMPLOYEE_MANAGE))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentEmployee()).thenReturn(plainEmployee);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");
        request.setManagerId(99L); // attempted direct managerId manipulation

        Employee updated = employeeService.updateEmployee(2L, request);

        // canManage is false, so the manager field must never be touched -
        // no lookup of id 99 should even happen.
        assertThat(updated.getManager()).isNull();
    }

    @Test
    void managerId_pointingToEmployee_isRejected() {

        Employee notAManager = Employee.builder().id(3L).name("Not A Manager").role(employeeRole).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        when(employeeRepository.findById(3L)).thenReturn(Optional.of(notAManager));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");
        request.setManagerId(3L);

        assertThatThrownBy(() -> employeeService.updateEmployee(2L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Manager role");
    }

    @Test
    void selfManagerRelationship_isRejected() {

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");
        request.setManagerId(2L); // self

        assertThatThrownBy(() -> employeeService.updateEmployee(2L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own manager");
    }

    @Test
    void managerToEmployee_withDirectReports_isRejected() {

        Role managerRole = managerRole();
        Employee managerBeingDemoted = Employee.builder().id(10L).name("Manager A").role(managerRole).build();
        Employee report = Employee.builder().id(11L).name("Report A1").role(employeeRole).manager(managerBeingDemoted).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(managerBeingDemoted));
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        when(employeeRepository.findByManagerId(10L)).thenReturn(List.of(report));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Manager A");
        request.setEmail("managera@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");

        assertThatThrownBy(() -> employeeService.updateEmployee(10L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot change role to EMPLOYEE because this employee has employees reporting to them. Reassign their direct reports first.");

        // The report itself must never be touched by this rejected request.
        assertThat(report.getManager()).isEqualTo(managerBeingDemoted);
    }

    @Test
    void managerToEmployee_withNoDirectReports_succeeds() {

        Role managerRole = managerRole();
        Employee managerBeingDemoted = Employee.builder().id(10L).name("Manager A").role(managerRole).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(managerBeingDemoted));
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        when(employeeRepository.findByManagerId(10L)).thenReturn(List.of());
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Manager A");
        request.setEmail("managera@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");

        Employee updated = employeeService.updateEmployee(10L, request);

        assertThat(updated.getRole()).isEqualTo(employeeRole);
    }

    @Test
    void deletingManager_withDirectReports_isRejected() {

        Role managerRole = managerRole();
        Employee managerBeingDeleted = Employee.builder().id(10L).name("Manager A").role(managerRole).build();
        Employee report = Employee.builder().id(11L).name("Report A1").role(employeeRole).manager(managerBeingDeleted).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(managerBeingDeleted));
        when(employeeRepository.findByManagerId(10L)).thenReturn(List.of(report));

        assertThatThrownBy(() -> employeeService.deleteEmployee(10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other employees report to them");
    }

    // ---------- ADMIN/MANAGER can never have a manager - the hierarchy is
    // one Manager level above Employees only, and Admin needs no manager at
    // all. A non-null managerId submitted alongside either role must be
    // rejected outright, not silently dropped, so the API can't be used to
    // bypass the "no Manager field" UI rule. ----------

    @Test
    void admin_cannotAssignManagerId_whenCreatingAdmin() {

        Role managerRole = managerRole();
        Employee existingManager = Employee.builder().id(20L).name("Manager A").role(managerRole).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(adminRole));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("New Admin");
        request.setEmail("newadmin@example.com");
        request.setPhone("9876543212");
        request.setPassword("password123");
        request.setRole("ADMIN");
        request.setManagerId(existingManager.getId());

        assertThatThrownBy(() -> employeeService.createEmployee(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN role cannot have a manager");
    }

    @Test
    void admin_cannotAssignManagerId_whenCreatingManager() {

        Role managerRole = managerRole();
        Employee anotherManager = Employee.builder().id(21L).name("Manager B").role(managerRole).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(roleRepository.findByNameIgnoreCase("MANAGER")).thenReturn(Optional.of(managerRole));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("New Manager");
        request.setEmail("newmanager2@example.com");
        request.setPhone("9876543213");
        request.setPassword("password123");
        request.setRole("MANAGER");
        request.setManagerId(anotherManager.getId());

        assertThatThrownBy(() -> employeeService.createEmployee(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANAGER role cannot have a manager");
    }

    @Test
    void promotingEmployeeToManager_clearsExistingManagerLink() {

        Role managerRole = managerRole();
        Employee oldManager = Employee.builder().id(30L).name("Old Manager").role(managerRole).build();
        Employee beingPromoted = Employee.builder().id(31L).name("Rising Star").role(employeeRole).manager(oldManager).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(31L)).thenReturn(Optional.of(beingPromoted));
        when(roleRepository.findByNameIgnoreCase("MANAGER")).thenReturn(Optional.of(managerRole));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Rising Star");
        request.setEmail("rising@example.com");
        request.setPhone("9876543214");
        request.setRole("MANAGER");
        // No managerId submitted - promotion must clear the old link itself.

        Employee updated = employeeService.updateEmployee(31L, request);

        assertThat(updated.getRole()).isEqualTo(managerRole);
        assertThat(updated.getManager()).isNull();
    }

    // ---------- Deleting an employee who still owns CRM records must fail
    // with a clear business error (not the generic DB-constraint message
    // GlobalExceptionHandler falls back to). ----------

    @Test
    void deletingEmployee_withAssignedLeads_isRejectedWithClearMessage() {

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(employeeRepository.findByManagerId(2L)).thenReturn(List.of());
        when(leadRepository.existsByAssignedEmployeeId(2L)).thenReturn(true);

        assertThatThrownBy(() -> employeeService.deleteEmployee(2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigned Leads");
    }

    @Test
    void deletingEmployee_withAssignedCustomers_isRejectedWithClearMessage() {

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(employeeRepository.findByManagerId(2L)).thenReturn(List.of());
        when(leadRepository.existsByAssignedEmployeeId(2L)).thenReturn(false);
        when(customerRepository.existsByAssignedEmployeeId(2L)).thenReturn(true);

        assertThatThrownBy(() -> employeeService.deleteEmployee(2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigned Customers");
    }

    @Test
    void deletingEmployee_withAssignedFollowUps_isRejectedWithClearMessage() {

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(employeeRepository.findByManagerId(2L)).thenReturn(List.of());
        when(leadRepository.existsByAssignedEmployeeId(2L)).thenReturn(false);
        when(customerRepository.existsByAssignedEmployeeId(2L)).thenReturn(false);
        when(followUpRepository.existsByEmployeeId(2L)).thenReturn(true);

        assertThatThrownBy(() -> employeeService.deleteEmployee(2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigned Follow-ups");
    }

    // ---------- Positive path: Admin can delete an Employee, a Manager, or
    // another Admin once there are no blocking dependencies. ----------

    private void stubNoDependencies(Long id) {
        when(employeeRepository.findByManagerId(id)).thenReturn(List.of());
        when(leadRepository.existsByAssignedEmployeeId(id)).thenReturn(false);
        when(customerRepository.existsByAssignedEmployeeId(id)).thenReturn(false);
        when(followUpRepository.existsByEmployeeId(id)).thenReturn(false);
    }

    @Test
    void admin_canDeleteEmployee_withNoDependencies() {

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        stubNoDependencies(2L);

        employeeService.deleteEmployee(2L);

        org.mockito.Mockito.verify(employeeRepository).delete(plainEmployee);
    }

    @Test
    void admin_canDeleteManager_withNoDependencies() {

        Role managerRole = managerRole();
        Employee manager = Employee.builder().id(10L).name("Manager A").role(managerRole).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(manager));
        stubNoDependencies(10L);

        employeeService.deleteEmployee(10L);

        org.mockito.Mockito.verify(employeeRepository).delete(manager);
    }

    @Test
    void admin_canDeleteAdmin_withNoDependencies() {

        Role secondAdminRole = Role.builder().id(1L).name("ADMIN").rank(100).build();
        Employee anotherAdmin = Employee.builder().id(40L).name("Admin Two").role(secondAdminRole).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(40L)).thenReturn(Optional.of(anotherAdmin));
        stubNoDependencies(40L);

        employeeService.deleteEmployee(40L);

        org.mockito.Mockito.verify(employeeRepository).delete(anotherAdmin);
    }

    // ---------- Activity logging: EmployeeService.updateEmployee diffs
    // old vs. new role/manager/isActive to pick the specific action - not a
    // simple 1:1 mapping like every other module, so worth testing each
    // branch directly rather than trusting it by inspection. ----------

    @Test
    void admin_creatingEmployee_logsEmployeeCreated() {

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        EmployeeRequest request = new EmployeeRequest();
        request.setName("New Hire");
        request.setEmail("newhire@example.com");
        request.setPhone("9876543215");
        request.setPassword("password123");
        request.setRole("EMPLOYEE");

        employeeService.createEmployee(request);

        verify(activityLogService).log(
                eq(admin), eq(ActivityModule.EMPLOYEE), eq(ActivityAction.EMPLOYEE_CREATED),
                eq(99L), any(), any());
    }

    @Test
    void admin_changingEmployeeRole_logsRoleChanged() {

        Role managerRole = managerRole();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(roleRepository.findByNameIgnoreCase("MANAGER")).thenReturn(Optional.of(managerRole));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("MANAGER");

        employeeService.updateEmployee(2L, request);

        verify(activityLogService).log(
                eq(admin), eq(ActivityModule.EMPLOYEE), eq(ActivityAction.ROLE_CHANGED),
                eq(2L), any(), any());
    }

    @Test
    void admin_changingEmployeeManager_logsManagerChanged() {

        Role managerRole = managerRole();
        Employee newManager = Employee.builder().id(50L).name("Manager X").role(managerRole).build();

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        when(employeeRepository.findById(50L)).thenReturn(Optional.of(newManager));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");
        request.setManagerId(50L);

        employeeService.updateEmployee(2L, request);

        verify(activityLogService).log(
                eq(admin), eq(ActivityModule.EMPLOYEE), eq(ActivityAction.MANAGER_CHANGED),
                eq(2L), any(), any());
    }

    @Test
    void admin_deactivatingEmployee_logsEmployeeDeactivated() {

        plainEmployee.setIsActive(true);

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");
        request.setIsActive(false);

        employeeService.updateEmployee(2L, request);

        verify(activityLogService).log(
                eq(admin), eq(ActivityModule.EMPLOYEE), eq(ActivityAction.EMPLOYEE_DEACTIVATED),
                eq(2L), any(), any());
    }

    @Test
    void admin_changingOnlyBasicFields_logsEmployeeUpdated() {

        plainEmployee.setIsActive(true);

        grant(adminRole, EMPLOYEE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(plainEmployee));
        when(roleRepository.findByNameIgnoreCase("EMPLOYEE")).thenReturn(Optional.of(employeeRole));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        EmployeeRequest request = new EmployeeRequest();
        request.setName("Priya Updated");
        request.setEmail("priya@example.com");
        request.setPhone("9876543210");
        request.setRole("EMPLOYEE");

        employeeService.updateEmployee(2L, request);

        verify(activityLogService).log(
                eq(admin), eq(ActivityModule.EMPLOYEE), eq(ActivityAction.EMPLOYEE_UPDATED),
                eq(2L), any(), any());
    }
}
