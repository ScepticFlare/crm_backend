package com.compact.crm.controller;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the GET /api/employees 500 seen for a MANAGER user.
 *
 * Root cause: EmployeeService.getAllEmployees(), for a MANAGER, first
 * resolves their team via AccessControlService.teamMemberIds(), which runs
 * "SELECT e FROM Employee e WHERE e.manager.id = :managerId" (no fetch
 * join). Loading each report that way populates that report's own `manager`
 * field as an *uninitialized Hibernate proxy* for the manager's id,
 * registered in the persistence context under (Employee, managerId).
 * getAllEmployees() then calls employeeRepository.findAllById(visibleIds),
 * which includes that same managerId - since an entity is already managed
 * under that identity, Hibernate reuses/initializes the *existing proxy*
 * in place rather than returning a plain Employee, so the manager's own
 * row in the returned list has runtime type Employee$HibernateProxy$...
 * instead of Employee. Jackson's default (non-static) type introspection
 * then serializes that proxy subclass, encountering the ByteBuddy-injected
 * hibernateLazyInitializer/handler properties, which it cannot serialize -
 * surfacing as the 500 from GlobalExceptionHandler's generic handler.
 *
 * Order matters: the team-lookup query must run before the id-list query,
 * in the same session, exactly as EmployeeService does it - a plain
 * findAll() (the ADMIN path) or clearing the persistence context first
 * does not reproduce this.
 */
@SpringBootTest
@AutoConfigureTestEntityManager
@Transactional
class EmployeeSerializationTest {

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void managerTeamList_serializesWithoutHibernateProxyLeaking() throws Exception {

        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        Employee manager = employeeRepository.save(Employee.builder()
                .name("Manager M").email("manager.m@example.com").phone("9000000101")
                .password("x").role(managerRole).build());

        Employee report = employeeRepository.save(Employee.builder()
                .name("Report R").email("report.r@example.com").phone("9000000102")
                .password("x").role(employeeRole).manager(manager).build());

        entityManager.flush();
        entityManager.clear();

        // Reproduces AccessControlService.teamMemberIds() + EmployeeService
        // .getAllEmployees() exactly, in the same order, in one session -
        // this ordering is what turns the manager's own list entry into a
        // Hibernate proxy.
        List<Employee> directReports = employeeRepository.findByManagerId(manager.getId());
        List<Long> visibleIds = new java.util.ArrayList<>();
        visibleIds.add(manager.getId());
        directReports.forEach(r -> visibleIds.add(r.getId()));

        List<Employee> team = employeeRepository.findAllById(visibleIds);

        // This is the exact failure mode reported in production: Jackson
        // throwing InvalidDefinitionException while trying to serialize
        // the Hibernate proxy's internal hibernateLazyInitializer/handler
        // properties instead of the plain Employee bean.
        String json = objectMapper.writeValueAsString(team);

        assertThat(json).doesNotContain("hibernateLazyInitializer");
        assertThat(json).doesNotContain("ByteBuddyInterceptor");
        assertThat(json).doesNotContain("\"handler\"");

        assertThat(json).contains("\"managerName\":\"Manager M\"");
        assertThat(json).contains("\"managerId\":" + manager.getId());
        assertThat(json).contains("\"name\":\"Report R\"");
        assertThat(json).contains("\"name\":\"Manager M\"");
    }
}
