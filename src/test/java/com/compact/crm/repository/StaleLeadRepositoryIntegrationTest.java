package com.compact.crm.repository;

import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database verification of LeadRepository.findStaleActiveLeads - the
 * query backing scheduler.StaleLeadScheduler's 6-month stale-lead ->
 * Inactive business rule. A Mockito-based scheduler test can only confirm
 * the loop wires status flips + logging correctly given a stubbed list;
 * only a real query against real rows (and real @PrePersist/@PreUpdate
 * timestamp behavior) can confirm which leads actually get selected.
 */
@DataJpaTest
class StaleLeadRepositoryIntegrationTest {

    private static final List<LeadStatus> ACTIVE_STATUSES = List.of(
            LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUOTATION_SENT, LeadStatus.NEGOTIATION
    );

    @Autowired private LeadRepository leadRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private SalesStageRepository salesStageRepository;
    @Autowired private EntityManager entityManager;

    private Employee employee;
    private Industry industry;
    private LeadSourceMaster leadSource;

    @BeforeEach
    void setUp() {

        Role role = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        employee = employeeRepository.save(Employee.builder()
                .name("Employee E").email("e@example.com").phone("9000000009")
                .password("x").role(role).build());

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());
    }

    private Lead saveLead(String companyName, LeadStatus status) {

        return leadRepository.save(Lead.builder()
                .companyName(companyName).contactPerson("Contact").phone("1234567890").email("x@x.com")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(status).leadValidity(LeadValidity.VALID)
                .assignedEmployee(employee).build());
    }

    // Bypasses the @PreUpdate lifecycle callback (a bulk JPQL update, unlike
    // repository.save(), never re-stamps updatedAt to now()) so a lead's
    // "last touched" time can be backdated to simulate staleness.
    private void backdateUpdatedAt(Lead lead, LocalDateTime when) {

        entityManager.createQuery("UPDATE Lead l SET l.updatedAt = :dt WHERE l.id = :id")
                .setParameter("dt", when)
                .setParameter("id", lead.getId())
                .executeUpdate();

        entityManager.clear();
    }

    @Test
    void leadTouchedWithinTheWindow_isNotStale() {

        Lead lead = saveLead("Fresh Co", LeadStatus.NEW);
        backdateUpdatedAt(lead, LocalDateTime.now().minusMonths(1));

        List<Lead> stale = leadRepository.findStaleActiveLeads(ACTIVE_STATUSES, LocalDateTime.now().minusMonths(6));

        assertThat(stale).isEmpty();
    }

    @Test
    void leadUntouchedPastTheWindow_isStale() {

        Lead lead = saveLead("Stale Co", LeadStatus.NEW);
        backdateUpdatedAt(lead, LocalDateTime.now().minusMonths(7));

        List<Lead> stale = leadRepository.findStaleActiveLeads(ACTIVE_STATUSES, LocalDateTime.now().minusMonths(6));

        assertThat(stale).extracting(Lead::getCompanyName).containsExactly("Stale Co");
    }

    @Test
    void recentFollowUpActivity_protectsALead_evenIfTheLeadRecordItselfWasNotEdited() {

        Lead lead = saveLead("Followed Co", LeadStatus.NEW);
        backdateUpdatedAt(lead, LocalDateTime.now().minusMonths(7));

        ActivityType callType = activityTypeRepository.save(ActivityType.builder().name("Call").build());

        // FollowUp's own @PrePersist stamps createdAt/updatedAt = now(),
        // well within the 6-month window, even though the parent Lead's
        // updatedAt is old.
        followUpRepository.save(FollowUp.builder()
                .lead(lead)
                .employee(employee)
                .activityType(callType)
                .status(FollowUpStatus.COMPLETED)
                .scheduledDate(LocalDateTime.now().minusDays(1))
                .build());

        List<Lead> stale = leadRepository.findStaleActiveLeads(ACTIVE_STATUSES, LocalDateTime.now().minusMonths(6));

        assertThat(stale).isEmpty();
    }

    @Test
    void alreadyInvalidLead_isNeverReselected_evenIfVeryOld() {

        // INVALID is a separate, manually-set business judgment (a
        // genuinely bad/unusable Lead) - the stale-lead job must never
        // touch it, exactly like every other resolved/terminal status.
        Lead lead = saveLead("Already Invalid Co", LeadStatus.INVALID);
        backdateUpdatedAt(lead, LocalDateTime.now().minusMonths(12));

        List<Lead> stale = leadRepository.findStaleActiveLeads(ACTIVE_STATUSES, LocalDateTime.now().minusMonths(6));

        assertThat(stale).isEmpty();
    }

    @Test
    void alreadyInactiveLead_isNeverReselected_evenIfVeryOld() {

        Lead lead = saveLead("Already Inactive Co", LeadStatus.INACTIVE);
        backdateUpdatedAt(lead, LocalDateTime.now().minusMonths(12));

        List<Lead> stale = leadRepository.findStaleActiveLeads(ACTIVE_STATUSES, LocalDateTime.now().minusMonths(6));

        assertThat(stale).isEmpty();
    }

    @Test
    void resolvedOutcomeLeads_areNeverTouched_evenIfVeryOld() {

        Lead won = saveLead("Old Won Co", LeadStatus.WON);
        backdateUpdatedAt(won, LocalDateTime.now().minusMonths(12));

        Lead lost = saveLead("Old Lost Co", LeadStatus.LOST);
        backdateUpdatedAt(lost, LocalDateTime.now().minusMonths(12));

        List<Lead> stale = leadRepository.findStaleActiveLeads(ACTIVE_STATUSES, LocalDateTime.now().minusMonths(6));

        assertThat(stale).isEmpty();
    }

    @Test
    void leadAlreadyConvertedToOpportunity_isNeverTouched_evenIfVeryOld() {

        Lead lead = saveLead("Converted Co", LeadStatus.NEW);
        backdateUpdatedAt(lead, LocalDateTime.now().minusMonths(12));

        SalesStage stage = salesStageRepository.save(SalesStage.builder().name("NEW").build());

        opportunityRepository.save(Opportunity.builder()
                .title("Converted Co Deal")
                .lead(lead)
                .salesStage(stage)
                .leadValidity(LeadValidity.VALID)
                .build());

        List<Lead> stale = leadRepository.findStaleActiveLeads(ACTIVE_STATUSES, LocalDateTime.now().minusMonths(6));

        assertThat(stale).isEmpty();
    }
}
