package com.compact.crm.service;

import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.EmployeeRepository;
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

import java.util.List;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.CUSTOMER_DELETE;
import static com.compact.crm.security.AccessControlService.CUSTOMER_EXPORT;
import static com.compact.crm.security.AccessControlService.CUSTOMER_MANAGE;
import static com.compact.crm.security.AccessControlService.CUSTOMER_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private OpportunityRepository opportunityRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private ActivityLogService activityLogService;

    private CustomerService customerService;

    private Role adminRole;
    private Role managerRole;
    private Role employeeRole;

    private Employee admin;
    private Employee managerA;
    private Employee managerB;
    private Employee reportOfA;

    private Customer customerOfReportOfA;
    private Customer customerOfManagerB;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        customerService = new CustomerService(
                customerRepository,
                employeeRepository,
                opportunityRepository,
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

        // Customer ownership is the Customer's own assignedEmployee (fixed
        // to be the single source of truth - see CustomerService).
        customerOfReportOfA = Customer.builder().id(300L).assignedEmployee(reportOfA).build();
        customerOfManagerB = Customer.builder().id(301L).assignedEmployee(managerB).build();
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
    void admin_canAccessCustomerFromAnyTeam() {

        grant(adminRole, CUSTOMER_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(customerRepository.findById(301L)).thenReturn(Optional.of(customerOfManagerB));

        assertThat(customerService.getCustomerById(301L)).isEqualTo(customerOfManagerB);
    }

    @Test
    void manager_canAccessDirectReportsCustomer() {

        grant(managerRole, CUSTOMER_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(customerRepository.findById(300L)).thenReturn(Optional.of(customerOfReportOfA));

        assertThat(customerService.getCustomerById(300L)).isEqualTo(customerOfReportOfA);
    }

    @Test
    void manager_cannotAccessAnotherTeamsCustomer_byRequestingItsIdDirectly() {

        grant(managerRole, CUSTOMER_VIEW, Scope.TEAM);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);
        when(employeeRepository.findByManagerId(managerA.getId())).thenReturn(List.of(reportOfA));
        when(customerRepository.findById(301L)).thenReturn(Optional.of(customerOfManagerB));

        assertThatThrownBy(() -> customerService.getCustomerById(301L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employee_cannotAccessAnotherEmployeesCustomer() {

        Employee anotherReportOfA = Employee.builder().id(6L).role(employeeRole).manager(managerA).build();
        Customer customerOfAnotherReportOfA = Customer.builder().id(302L).assignedEmployee(anotherReportOfA).build();

        grant(employeeRole, CUSTOMER_VIEW, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(customerRepository.findById(302L)).thenReturn(Optional.of(customerOfAnotherReportOfA));

        assertThatThrownBy(() -> customerService.getCustomerById(302L))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- delete/export authorization: ADMIN-only business rule
    // (2026-08-19). Split out of CUSTOMER_MANAGE into its own admin-only
    // permission codes - Manager (previously TEAM-scoped delete via
    // CUSTOMER_MANAGE) and Employee must now be rejected outright. ----------

    @Test
    void bulkDeleteCustomers_admin_deletesAcrossTeams() {

        grant(adminRole, CUSTOMER_DELETE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(customerRepository.findById(300L)).thenReturn(Optional.of(customerOfReportOfA));
        when(customerRepository.findById(301L)).thenReturn(Optional.of(customerOfManagerB));

        com.compact.crm.dto.response.BulkOperationResult result =
                customerService.bulkDeleteCustomers(List.of(300L, 301L));

        assertThat(result.getSucceededIds()).containsExactlyInAnyOrder(300L, 301L);
        org.mockito.Mockito.verify(customerRepository).delete(customerOfReportOfA);
        org.mockito.Mockito.verify(customerRepository).delete(customerOfManagerB);
    }

    @Test
    void bulkDeleteCustomers_manager_rejectedOutright_evenForOwnTeamRecords() {

        deny(managerRole, CUSTOMER_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        assertThatThrownBy(() -> customerService.bulkDeleteCustomers(List.of(300L)))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(customerRepository, org.mockito.Mockito.never()).delete(any(Customer.class));
    }

    @Test
    void deleteCustomer_employee_rejected() {

        deny(employeeRole, CUSTOMER_DELETE);
        when(currentUserService.getCurrentEmployee()).thenReturn(reportOfA);
        when(customerRepository.findById(300L)).thenReturn(Optional.of(customerOfReportOfA));

        assertThatThrownBy(() -> customerService.deleteCustomer(300L))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(customerRepository, org.mockito.Mockito.never()).delete(any(Customer.class));
    }

    @Test
    void admin_canExportCustomers() {

        grant(adminRole, CUSTOMER_EXPORT, Scope.ALL);
        grant(adminRole, CUSTOMER_VIEW, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(customerRepository.findAll(
                org.mockito.ArgumentMatchers.nullable(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(customerOfReportOfA)));

        List<Customer> result = customerService.findForExport(
                new com.compact.crm.dto.search.CustomerSearchCriteria(), null, null, null);

        assertThat(result).containsExactly(customerOfReportOfA);
    }

    @Test
    void manager_cannotExportCustomers() {

        deny(managerRole, CUSTOMER_EXPORT);
        when(currentUserService.getCurrentEmployee()).thenReturn(managerA);

        assertThatThrownBy(() -> customerService.findForExport(
                new com.compact.crm.dto.search.CustomerSearchCriteria(), null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
