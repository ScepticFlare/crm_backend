package com.compact.crm.service;

import com.compact.crm.dto.request.EmailSuppressionRequest;
import com.compact.crm.dto.response.EmailSuppressionAuditResponse;
import com.compact.crm.dto.response.EmailSuppressionResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.Scope;
import com.compact.crm.enums.SuppressionReason;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.EmailSuppressionAuditRepository;
import com.compact.crm.repository.EmailSuppressionRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The do-not-email list: address normalization, admin-only management and the audit trail. */
@DataJpaTest
@Import({EmailSuppressionService.class, AccessControlService.class, CurrentUserService.class})
class EmailSuppressionServiceTest {

    @Autowired private EmailSuppressionService service;
    @Autowired private EmailSuppressionRepository repository;
    @Autowired private EmailSuppressionAuditRepository auditRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;

    private Employee admin;
    private Employee manager;
    private Employee employee;

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        Permission manage = permissionRepository.save(
                Permission.builder().code(AccessControlService.EMAIL_SUPPRESSION_MANAGE).build());
        Permission send = permissionRepository.save(
                Permission.builder().code(AccessControlService.EMAIL_SEND).build());

        // as in V13: only ADMIN holds the management permission
        rolePermissionRepository.save(RolePermission.builder().role(adminRole).permission(manage).scope(Scope.ALL).build());
        rolePermissionRepository.save(RolePermission.builder().role(adminRole).permission(send).scope(Scope.ALL).build());
        rolePermissionRepository.save(RolePermission.builder().role(managerRole).permission(send).scope(Scope.TEAM).build());
        rolePermissionRepository.save(RolePermission.builder().role(employeeRole).permission(send).scope(Scope.OWN).build());

        admin = employeeRepository.save(Employee.builder().name("Admin One").email("adm@t.test").phone("9500000001")
                .password("x").role(adminRole).build());
        manager = employeeRepository.save(Employee.builder().name("Manager").email("mgr@t.test").phone("9500000002")
                .password("x").role(managerRole).build());
        employee = employeeRepository.save(Employee.builder().name("Employee").email("emp@t.test").phone("9500000003")
                .password("x").role(employeeRole).build());

        authenticateAs(admin);
    }

    // ------------------------------------------------------------ the list itself

    @Test
    void addressesAreNormalized_soEveryCaseAndSpacingVariantIsBlocked() {

        EmailSuppressionResponse added = service.add(request("  Person@Example.COM ", SuppressionReason.OPT_OUT, "asked to stop"));

        assertThat(added.getEmail()).isEqualTo("person@example.com");
        assertThat(added.getSource()).isEqualTo("ADMIN_CONSOLE");
        assertThat(added.getCreatedByName()).isEqualTo("Admin One");

        assertThat(service.isSuppressed("person@example.com")).isTrue();
        assertThat(service.isSuppressed("PERSON@EXAMPLE.COM")).isTrue();
        assertThat(service.isSuppressed(" Person@Example.com ")).isTrue();
        assertThat(service.isSuppressed("other@example.com")).isFalse();
        assertThat(service.isSuppressed(null)).isFalse();
        assertThat(service.isSuppressed("   ")).isFalse();

        assertThat(service.findSuppressed(List.of("PERSON@example.com", "x@y.test", " ", ""))).isEqualTo(Set.of("person@example.com"));
        assertThat(service.findSuppressed(List.of())).isEmpty();
    }

    @Test
    void invalidAndDuplicateAddressesAreRejected() {

        assertThatThrownBy(() -> service.add(request("not an email", SuppressionReason.MANUAL, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("valid email");

        service.add(request("dup@example.com", SuppressionReason.BOUNCE, null));

        assertThatThrownBy(() -> service.add(request("DUP@example.com", SuppressionReason.COMPLAINT, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already");

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void nothingIsSeededOrChangedAutomatically() {
        assertThat(repository.count()).isZero();
        assertThat(auditRepository.count()).isZero();
    }

    // ------------------------------------------------------------ authorization

    @Test
    void onlyAnAdminMayListAddRemoveOrReadTheAudit() {

        EmailSuppressionResponse existing = service.add(request("keep@example.com", SuppressionReason.OPT_OUT, null));

        for (Employee other : List.of(manager, employee)) {

            authenticateAs(other);

            assertThatThrownBy(() -> service.list(null, 0, 25)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.add(request("new@example.com", SuppressionReason.MANUAL, null)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.remove(existing.getId(), "I would like to email them again"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.audit(0, 25)).isInstanceOf(AccessDeniedException.class);

            // ...but the enforcement check itself works for every authenticated flow
            assertThat(service.isSuppressed("keep@example.com")).isTrue();
        }

        assertThat(repository.count()).isEqualTo(1);
        assertThat(auditRepository.count()).isEqualTo(1);        // only the admin's own ADDED entry
    }

    @Test
    void listingSupportsSearchAndPaging() {

        service.add(request("alpha@example.com", SuppressionReason.OPT_OUT, null));
        service.add(request("beta@example.com", SuppressionReason.BOUNCE, null));
        service.add(request("gamma@other.test", SuppressionReason.COMPLAINT, null));

        assertThat(service.list(null, 0, 2).getItems()).hasSize(2);
        assertThat(service.list(null, 0, 2).getTotal()).isEqualTo(3);
        assertThat(service.list("EXAMPLE", 0, 25).getItems()).extracting(EmailSuppressionResponse::getEmail)
                .containsExactlyInAnyOrder("alpha@example.com", "beta@example.com");
    }

    // ------------------------------------------------------------ audit

    @Test
    void everyAddAndRemoval_isAudited_andARemovalNeedsAJustification() {

        EmailSuppressionResponse added = service.add(request("audit@example.com", SuppressionReason.COMPLAINT, "spam complaint"));

        assertThatThrownBy(() -> service.remove(added.getId(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.remove(added.getId(), "ok")).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.count()).isEqualTo(1);                       // refused removals change nothing

        authenticateAs(admin);
        service.remove(added.getId(), "  Customer re-subscribed in writing, ticket 4711  ");

        assertThat(repository.count()).isZero();
        assertThat(service.isSuppressed("audit@example.com")).isFalse();

        List<EmailSuppressionAuditResponse> audit = service.audit(0, 25).getItems();

        assertThat(audit).extracting(EmailSuppressionAuditResponse::getAction).containsExactly("REMOVED", "ADDED");

        EmailSuppressionAuditResponse removed = audit.get(0);
        assertThat(removed.getEmail()).isEqualTo("audit@example.com");
        assertThat(removed.getReason()).isEqualTo(SuppressionReason.COMPLAINT);
        assertThat(removed.getNote()).isEqualTo("Customer re-subscribed in writing, ticket 4711");
        assertThat(removed.getPerformedByEmployeeId()).isEqualTo(admin.getId());
        assertThat(removed.getPerformedByName()).isEqualTo("Admin One");
        assertThat(removed.getPerformedAt()).isNotNull();

        assertThat(audit.get(1).getNote()).isEqualTo("spam complaint");
    }

    @Test
    void removingAnUnknownEntryIsA404_andLeavesNoAuditRow() {

        assertThatThrownBy(() -> service.remove(424_242L, "does not exist anywhere"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(auditRepository.count()).isZero();
    }

    // ------------------------------------------------------------ helpers

    private EmailSuppressionRequest request(String email, SuppressionReason reason, String note) {
        return new EmailSuppressionRequest(email, reason, note);
    }

    private void authenticateAs(Employee actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }
}
