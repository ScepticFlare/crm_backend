package com.compact.crm.specification;

import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Role;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.repository.BatteryRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database verification of LeadSpecifications - the Mockito-based
 * service tests can confirm the plumbing calls resolveVisibleEmployeeIds,
 * but only a real query against real rows can confirm the generated
 * Criteria-API predicates actually filter/sort correctly (search, single
 * filters, combined filters, and the RBAC ownerIn scope predicate).
 */
@DataJpaTest
class LeadSpecificationsIntegrationTest {

    @Autowired private LeadRepository leadRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private BatteryRepository batteryRepository;

    private Employee employeeA;
    private Employee employeeB;
    private Industry manufacturing;
    private Battery batteryX;

    @BeforeEach
    void setUp() {

        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        Employee manager = employeeRepository.save(Employee.builder()
                .name("Manager M").email("m@example.com").phone("9000000001")
                .password("x").role(employeeRole).build());

        employeeA = employeeRepository.save(Employee.builder()
                .name("Employee A").email("a@example.com").phone("9000000002")
                .password("x").role(employeeRole).manager(manager).build());

        employeeB = employeeRepository.save(Employee.builder()
                .name("Employee B").email("b@example.com").phone("9000000003")
                .password("x").role(employeeRole).build());

        manufacturing = industryRepository.save(Industry.builder().name("Manufacturing").build());
        Industry it = industryRepository.save(Industry.builder().name("IT").build());

        LeadSourceMaster referral = leadSourceMasterRepository.save(
                LeadSourceMaster.builder().name("Referral").build());

        batteryX = batteryRepository.save(Battery.builder().name("Battery X").build());
        batteryRepository.save(Battery.builder().name("Battery Y").build());

        Lead acme = leadRepository.save(Lead.builder()
                .companyName("Acme Corp").contactPerson("Alice").phone("1111111111").email("alice@acme.com")
                .city("Pune").state("MH").industry(manufacturing).leadSource(referral)
                .leadStatus(LeadStatus.NEW).leadValidity(LeadValidity.VALID)
                .assignedEmployee(employeeA).build());

        acme.getLeadBatteries().add(
                LeadBattery.builder().lead(acme).battery(batteryX).quantity(2).build());
        leadRepository.save(acme);

        leadRepository.save(Lead.builder()
                .companyName("Beta Inc").contactPerson("Bob").phone("2222222222").email("bob@beta.com")
                .city("Mumbai").state("MH").industry(it).leadSource(referral)
                .leadStatus(LeadStatus.LOST).leadValidity(LeadValidity.VALID)
                .assignedEmployee(employeeB).build());
    }

    @Test
    void search_matchesCompanyNameCaseInsensitively() {

        List<Lead> results = leadRepository.findAll(LeadSpecifications.search("acme"));

        assertThat(results).extracting(Lead::getCompanyName).containsExactly("Acme Corp");
    }

    @Test
    void hasIndustryId_filtersToMatchingIndustryOnly() {

        List<Lead> results = leadRepository.findAll(LeadSpecifications.hasIndustryId(manufacturing.getId()));

        assertThat(results).extracting(Lead::getCompanyName).containsExactly("Acme Corp");
    }

    @Test
    void hasBatteryId_filtersToLeadsWithThatBatteryOnly() {

        List<Lead> results = leadRepository.findAll(LeadSpecifications.hasBatteryId(batteryX.getId()));

        assertThat(results).extracting(Lead::getCompanyName).containsExactly("Acme Corp");
    }

    @Test
    void hasBatteryId_null_meansNoFilter() {

        List<Lead> results = leadRepository.findAll(LeadSpecifications.hasBatteryId(null));

        assertThat(results).hasSize(2);
    }

    @Test
    void hasStatus_filtersToMatchingStatusOnly() {

        List<Lead> results = leadRepository.findAll(LeadSpecifications.hasStatus(LeadStatus.LOST));

        assertThat(results).extracting(Lead::getCompanyName).containsExactly("Beta Inc");
    }

    @Test
    void combinedFilters_areAndedTogether() {

        // "a" matches both company names (Acme, Bet-a) - combining it with
        // city=Pune should narrow the result down to just Acme, whose
        // city is the only one that also matches.
        Specification<Lead> combined = SpecificationUtil.and(List.of(
                LeadSpecifications.search("a"),
                LeadSpecifications.hasCity("Pune")
        ));

        List<Lead> results = leadRepository.findAll(combined);

        assertThat(results).extracting(Lead::getCompanyName).containsExactly("Acme Corp");
    }

    @Test
    void ownerIn_managerScope_excludesRecordsOutsideTheTeam() {

        // Manager M's team is themselves + Employee A only (Employee B
        // reports to nobody / a different manager) - ownerIn([A.id]) must
        // never surface Beta Inc, which belongs to Employee B.
        List<Lead> results = leadRepository.findAll(LeadSpecifications.ownerIn(List.of(employeeA.getId())));

        assertThat(results).extracting(Lead::getCompanyName).containsExactly("Acme Corp");
    }

    @Test
    void ownerIn_null_meansNoFilter() {

        List<Lead> results = leadRepository.findAll(LeadSpecifications.ownerIn(null));

        assertThat(results).hasSize(2);
    }

    @Test
    void sorting_byCompanyName_isApplied() {

        var page = leadRepository.findAll(
                (Specification<Lead>) null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "companyName"))
        );

        assertThat(page.getContent()).extracting(Lead::getCompanyName)
                .containsExactly("Acme Corp", "Beta Inc");
    }

    @Test
    void searchAndOwnerScope_combineToDenyOutOfScopeMatch() {

        // Employee B's own record matches search "beta" but Manager M's
        // visible scope is only [A] - the AND of the two specs must yield
        // nothing, not Beta Inc, even though the free-text search alone
        // would match it.
        Specification<Lead> combined = SpecificationUtil.and(List.of(
                LeadSpecifications.ownerIn(List.of(employeeA.getId())),
                LeadSpecifications.search("beta")
        ));

        List<Lead> results = leadRepository.findAll(combined);

        assertThat(results).isEmpty();
    }
}
