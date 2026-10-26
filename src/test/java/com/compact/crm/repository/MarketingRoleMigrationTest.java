package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real V11__add_marketing_role.sql against the seeded V2/V5 grants
 * (as Hibernate-created tables on H2 in PostgreSQL mode - Flyway itself is
 * disabled in tests, so this is a faithful-SQL check, not a Flyway-on-
 * PostgreSQL run). Verifies the MARKETING role gets exactly the EMPLOYEE
 * grants plus lead/opportunity export at OWN scope, that nothing else
 * changes, that nobody is assigned the role, and that re-running is a no-op.
 */
@DataJpaTest
class MarketingRoleMigrationTest {

    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DataSource dataSource;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    private final Map<String, Permission> permissions = new HashMap<>();

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissions.computeIfAbsent(
                code, c -> permissionRepository.save(Permission.builder().code(c).build()));

        rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
    }

    private void runMigration() throws Exception {

        roleRepository.flush();
        rolePermissionRepository.flush();

        // the connection bound to the test transaction, so the script sees the
        // (uncommitted) seed rows and its inserts roll back with the test
        Connection c = DataSourceUtils.getConnection(dataSource);

        ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V11__add_marketing_role.sql"));

        entityManager.clear();
    }

    @Test
    void marketingRole_getsEmployeeGrantsPlusLeadAndOpportunityExportAtOwn_andRerunIsANoOp() throws Exception {

        Role admin = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role employee = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        for (String code : List.of("LEAD_VIEW", "LEAD_MANAGE", "OPPORTUNITY_VIEW", "OPPORTUNITY_MANAGE")) {
            grant(admin, code, Scope.ALL);
            grant(employee, code, Scope.OWN);
        }

        for (String code : List.of("LEAD_EXPORT", "OPPORTUNITY_EXPORT", "CUSTOMER_EXPORT", "LEAD_DELETE")) {
            grant(admin, code, Scope.ALL);
        }

        employeeRepository.save(Employee.builder()
                .name("E").email("e@m.com").phone("9100000001").password("x").role(employee).build());

        long adminGrantsBefore = rolePermissionRepository.findAll().stream()
                .filter(rp -> rp.getRole().getName().equals("ADMIN")).count();
        long employeeGrantsBefore = rolePermissionRepository.findAll().stream()
                .filter(rp -> rp.getRole().getName().equals("EMPLOYEE")).count();

        runMigration();
        runMigration();   // idempotent

        rolePermissionRepository.flush();

        Role marketing = roleRepository.findByNameIgnoreCase("MARKETING").orElseThrow();

        assertThat(roleRepository.findAll().stream().filter(r -> r.getName().equals("MARKETING"))).hasSize(1);

        Map<String, Scope> marketingGrants = new HashMap<>();
        rolePermissionRepository.findAll().stream()
                .filter(rp -> rp.getRole().getId().equals(marketing.getId()))
                .forEach(rp -> marketingGrants.put(rp.getPermission().getCode(), rp.getScope()));

        assertThat(marketingGrants)
                .containsEntry("LEAD_VIEW", Scope.OWN)
                .containsEntry("LEAD_MANAGE", Scope.OWN)
                .containsEntry("OPPORTUNITY_VIEW", Scope.OWN)
                .containsEntry("OPPORTUNITY_MANAGE", Scope.OWN)
                .containsEntry("LEAD_EXPORT", Scope.OWN)
                .containsEntry("OPPORTUNITY_EXPORT", Scope.OWN)
                .doesNotContainKeys("CUSTOMER_EXPORT", "LEAD_DELETE")
                .hasSize(6);

        // existing roles untouched; the plain employee still has no export
        assertThat(rolePermissionRepository.findAll().stream()
                .filter(rp -> rp.getRole().getName().equals("ADMIN")).count()).isEqualTo(adminGrantsBefore);
        assertThat(rolePermissionRepository.findAll().stream()
                .filter(rp -> rp.getRole().getName().equals("EMPLOYEE")).count()).isEqualTo(employeeGrantsBefore);

        // nobody is silently given the new role
        assertThat(employeeRepository.findAll()).allMatch(e -> !e.getRoleName().equals("MARKETING"));
    }
}
