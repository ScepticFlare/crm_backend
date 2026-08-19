package com.compact.crm.service;

import com.compact.crm.dto.request.EmployeeRequest;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static com.compact.crm.security.AccessControlService.EMPLOYEE_MANAGE;
import static com.compact.crm.security.AccessControlService.EMPLOYEE_VIEW;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;
    private final LeadRepository leadRepository;
    private final CustomerRepository customerRepository;
    private final FollowUpRepository followUpRepository;
    private final ActivityLogService activityLogService;

    public Employee createEmployee(EmployeeRequest request) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (!accessControlService.hasPermission(currentEmployee, EMPLOYEE_MANAGE)) {
            throw new AccessDeniedException(
                    "Only administrators can create employees.");
        }

        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("Password is required.");
        }

        Role role = resolveRole(request.getRole());
        Employee manager = resolveManager(role, request.getManagerId(), null);

        Employee employee = Employee.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .manager(manager)
                .build();

        Employee saved = employeeRepository.save(employee);

        activityLogService.log(
                currentEmployee,
                ActivityModule.EMPLOYEE, ActivityAction.EMPLOYEE_CREATED,
                saved.getId(), saved.getName(),
                "Created employee (" + role.getName() + ")"
        );

        return saved;
    }

    // ADMIN (EMPLOYEE_VIEW=ALL) sees every employee. MANAGER
    // (EMPLOYEE_VIEW=TEAM) sees only themselves + their direct reports -
    // enough to populate an "Assigned Employee" picker scoped to their own
    // team. EMPLOYEE has no EMPLOYEE_VIEW grant at all, so this still 403s
    // for a plain employee exactly as it did before this permission existed.
    public List<Employee> getAllEmployees() {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        List<Long> visibleIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, EMPLOYEE_VIEW);

        if (visibleIds == null) {
            return employeeRepository.findAll();
        }

        return employeeRepository.findAllById(visibleIds);
    }

    public Employee getEmployeeById(Long id) {

        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Employee not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        boolean isSelf = employee.getId().equals(currentEmployee.getId());

        if (!isSelf && !accessControlService.hasPermission(currentEmployee, EMPLOYEE_MANAGE)) {
            throw new AccessDeniedException(
                    "You are not authorized to view this employee.");
        }

        return employee;
    }

    public Employee updateEmployee(Long id, EmployeeRequest request) {

        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Employee not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        boolean isSelf = employee.getId().equals(currentEmployee.getId());
        boolean canManage = accessControlService.hasPermission(currentEmployee, EMPLOYEE_MANAGE);

        if (!isSelf && !canManage) {
            throw new AccessDeniedException(
                    "You are not authorized to update this employee.");
        }

        // Captured before mutation so the write path below can log exactly
        // what changed, without re-deriving it from a diff against the
        // saved row afterward.
        boolean basicFieldsChanged =
                !Objects.equals(employee.getName(), request.getName())
                        || !Objects.equals(employee.getEmail(), request.getEmail())
                        || !Objects.equals(employee.getPhone(), request.getPhone())
                        || (request.getPassword() != null && !request.getPassword().isBlank());

        Role oldRole = employee.getRole();
        Employee oldManager = employee.getManager();
        Boolean oldIsActive = employee.getIsActive();

        employee.setName(request.getName());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());

        boolean roleChanged = false;
        boolean managerChanged = false;
        boolean activationChanged = false;
        Boolean newIsActive = oldIsActive;

        // Role and manager assignment are administrative changes - only an
        // employee with EMPLOYEE_MANAGE (i.e. an admin) may set them, even
        // when editing their own record. Without this gate, any employee
        // editing their own profile could promote themselves to ADMIN.
        if (canManage) {

            Role newRole = resolveRole(request.getRole());

            // Must run before the role is actually changed below - it reads
            // the employee's *current* direct-report list, which only makes
            // sense pre-mutation.
            assertRoleChangeAllowed(employee, newRole);

            Employee manager = resolveManager(newRole, request.getManagerId(), employee.getId());

            roleChanged = oldRole == null || !oldRole.getId().equals(newRole.getId());

            Long oldManagerId = oldManager != null ? oldManager.getId() : null;
            Long newManagerId = manager != null ? manager.getId() : null;
            managerChanged = !Objects.equals(oldManagerId, newManagerId);

            employee.setRole(newRole);
            employee.setManager(manager);

            // Null means "leave as-is" (see EmployeeRequest.isActive).
            newIsActive = request.getIsActive() != null ? request.getIsActive() : oldIsActive;
            activationChanged = !Objects.equals(oldIsActive, newIsActive);
            employee.setIsActive(newIsActive);
        }

        if (request.getPassword() != null &&
                !request.getPassword().isBlank()) {

            employee.setPassword(
                    passwordEncoder.encode(request.getPassword()));
        }

        Employee saved = employeeRepository.save(employee);

        if (roleChanged) {
            activityLogService.log(
                    currentEmployee,
                    ActivityModule.EMPLOYEE, ActivityAction.ROLE_CHANGED,
                    saved.getId(), saved.getName(),
                    "Changed role to " + saved.getRoleName()
            );
        }

        if (managerChanged) {
            activityLogService.log(
                    currentEmployee,
                    ActivityModule.EMPLOYEE, ActivityAction.MANAGER_CHANGED,
                    saved.getId(), saved.getName(),
                    saved.getManagerName() != null
                            ? "Changed manager to " + saved.getManagerName()
                            : "Removed manager"
            );
        }

        if (activationChanged) {
            activityLogService.log(
                    currentEmployee,
                    ActivityModule.EMPLOYEE,
                    Boolean.TRUE.equals(newIsActive) ? ActivityAction.EMPLOYEE_ACTIVATED : ActivityAction.EMPLOYEE_DEACTIVATED,
                    saved.getId(), saved.getName(),
                    Boolean.TRUE.equals(newIsActive) ? "Activated employee" : "Deactivated employee"
            );
        }

        if (basicFieldsChanged && !roleChanged && !managerChanged && !activationChanged) {
            activityLogService.log(
                    currentEmployee,
                    ActivityModule.EMPLOYEE, ActivityAction.EMPLOYEE_UPDATED,
                    saved.getId(), saved.getName(),
                    "Updated employee details"
            );
        }

        return saved;
    }

    public void deleteEmployee(Long id) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (!accessControlService.hasPermission(currentEmployee, EMPLOYEE_MANAGE)) {
            throw new AccessDeniedException(
                    "Only administrators can delete employees.");
        }


        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Employee not found"));
        if (currentEmployee.getId().equals(employee.getId())) {
            throw new IllegalArgumentException(
                    "You cannot delete your own account."
            );
        }

        // Deleting a manager out from under their reports would either
        // leave those rows pointing at a manager_id that no longer exists
        // (an FK violation surfaced as an opaque "Unable to save the
        // record.") or, if the FK were ever relaxed, silently orphan them.
        // Same corrective action as the demotion case below: reassign the
        // reports first.
        if (!employeeRepository.findByManagerId(employee.getId()).isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot delete this employee because other employees report to them. " +
                    "Reassign their direct reports first."
            );
        }

        // Lead/Customer/FollowUp all hold a plain FK to employees with no
        // cascade behavior, so deleting an employee who still owns any of
        // these would otherwise surface as an opaque "Unable to save the
        // record." from GlobalExceptionHandler's DataIntegrityViolation
        // handler. Checked explicitly here so the caller gets a clear,
        // actionable message instead.
        if (leadRepository.existsByAssignedEmployeeId(employee.getId())) {
            throw new IllegalArgumentException(
                    "Cannot delete this employee because they have assigned Leads. " +
                    "Reassign their Leads first."
            );
        }

        if (customerRepository.existsByAssignedEmployeeId(employee.getId())) {
            throw new IllegalArgumentException(
                    "Cannot delete this employee because they have assigned Customers. " +
                    "Reassign their Customers first."
            );
        }

        if (followUpRepository.existsByEmployeeId(employee.getId())) {
            throw new IllegalArgumentException(
                    "Cannot delete this employee because they have assigned Follow-ups. " +
                    "Reassign their Follow-ups first."
            );
        }

        employeeRepository.delete(employee);
    }

    private Role resolveRole(String roleName) {

        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("Role is required.");
        }

        return roleRepository.findByNameIgnoreCase(roleName.trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown role: " + roleName));
    }

    private static final String MANAGER_ROLE_NAME = "MANAGER";

    private boolean isManagerRole(Role role) {
        return role != null && MANAGER_ROLE_NAME.equalsIgnoreCase(role.getName());
    }

    // Enforces the hierarchy invariants for managerId:
    //  - ADMIN and MANAGER can never have a manager - the hierarchy is one
    //    Manager level above Employees only, and Admin needs no manager at
    //    all. A non-null managerId submitted alongside either of these
    //    roles is rejected outright (not silently nulled), so a caller
    //    can't bypass the UI-level "no Manager field" rule by hitting the
    //    API directly. This also covers the Employee -> Manager promotion
    //    edge case: promoting to MANAGER forces manager=null in the same
    //    request rather than leaving a stale manager link in place.
    //  - EMPLOYEE's managerId, when present, must point to an employee
    //    whose CURRENT role is MANAGER (never another EMPLOYEE or ADMIN).
    // selfEmployeeId is the id of the employee being edited (null when
    // creating a new one, since it can't reference itself yet) - used to
    // reject self-manager assignments.
    private Employee resolveManager(Role newRole, Long managerId, Long selfEmployeeId) {

        if (!isEmployeeRole(newRole)) {
            if (managerId != null) {
                throw new IllegalArgumentException(
                        "The " + newRole.getName() + " role cannot have a manager.");
            }
            return null;
        }

        if (managerId == null) {
            return null;
        }

        if (managerId.equals(selfEmployeeId)) {
            throw new IllegalArgumentException("An employee cannot be their own manager.");
        }

        Employee manager = employeeRepository.findById(managerId)
                .orElseThrow(() -> new ResourceNotFoundException("Manager not found"));

        if (!isManagerRole(manager.getRole())) {
            throw new IllegalArgumentException(
                    "The selected manager must currently have the Manager role.");
        }

        return manager;
    }

    private static final String EMPLOYEE_ROLE_NAME = "EMPLOYEE";

    private boolean isEmployeeRole(Role role) {
        return role != null && EMPLOYEE_ROLE_NAME.equalsIgnoreCase(role.getName());
    }

    // Blocks a role change that would leave this employee's existing
    // direct reports pointing their managerId at someone who is no longer
    // a Manager (the exact bug this method exists to prevent: Manager ->
    // Employee while still having reports). Reports are never touched here
    // - the caller must reassign or clear them first, then retry.
    private void assertRoleChangeAllowed(Employee employee, Role newRole) {

        if (isManagerRole(newRole)) {
            return;
        }

        List<Employee> directReports = employeeRepository.findByManagerId(employee.getId());

        if (!directReports.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot change role to " + newRole.getName() +
                    " because this employee has employees reporting to them. " +
                    "Reassign their direct reports first."
            );
        }
    }
}
