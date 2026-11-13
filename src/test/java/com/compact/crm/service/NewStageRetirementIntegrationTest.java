package com.compact.crm.service;

import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.repository.SalesStageRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.compact.crm.security.AccessControlService.FOLLOWUP_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retirement of the NEW opportunity stage (service.NewStageRetirement), on a
 * real H2 database with the real services:
 *  - NEW opportunities become IN PROGRESS, every other column untouched, and
 *    are stamped "Previously New" (stored provenance) with a history entry;
 *  - opportunities that were always IN PROGRESS are not marked;
 *  - the NEW row is kept but inactive, never re-created, never reactivated,
 *    and can never be chosen again;
 *  - the indicator survives later edits and stage changes;
 *  - running again changes nothing.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, FollowUpService.class, CustomerService.class, SalesStageService.class,
        ActivityLogService.class, AccessControlService.class, CurrentUserService.class, NewStageRetirement.class})
class NewStageRetirementIntegrationTest {

    @Autowired private NewStageRetirement newStageRetirement;
    @Autowired private OpportunityService opportunityService;
    @Autowired private SalesStageService salesStageService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private SalesStageRepository salesStageRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private EntityManager entityManager;

    private Employee admin;
    private Industry industry;
    private LeadSourceMaster leadSource;
    private SalesStage newStage;

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());

        for (String code : List.of(LEAD_MANAGE, LEAD_VIEW, OPPORTUNITY_MANAGE, OPPORTUNITY_VIEW, FOLLOWUP_MANAGE)) {
            Permission p = permissionRepository.save(Permission.builder().code(code).build());
            rolePermissionRepository.save(RolePermission.builder().role(adminRole).permission(p).scope(Scope.ALL).build());
        }

        admin = employeeRepository.save(Employee.builder()
                .name("Admin").email("admin@t.com").phone("9300000001").password("x").role(adminRole).build());

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());

        // An existing installation: the NEW stage row is there (typed, active)
        // from before the retirement; ensureCoreStages leaves it typed NEW.
        newStage = salesStageRepository.save(SalesStage.builder().name("NEW").stageType(SalesStageType.NEW).build());
        salesStageService.ensureCoreStages();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null, List.of()));
    }

    private SalesStage stageOfType(SalesStageType type) {
        return salesStageRepository.findByStageType(type).get(0);
    }

    private Lead lead(String company) {
        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("C").phone("1234567890")
                .email(company.toLowerCase().replace(" ", "") + "@example.com")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.VALID).assignedEmployee(admin).build());
    }

    // An opportunity as it already sits in the database.
    private Opportunity stored(String company, SalesStage stage) {
        return opportunityRepository.save(Opportunity.builder()
                .title(company + " deal").productValue(12345.5)
                .expectedClosingDate(LocalDate.of(2027, 1, 15))
                .salesStage(stage).leadValidity(LeadValidity.VALID)
                .contactPerson("Asha").contactPhone("9876543210").contactEmail("asha@example.com")
                .lead(lead(company)).build());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Opportunity reload(Long id) {
        return opportunityRepository.findById(id).orElseThrow();
    }

    private List<ActivityLog> history(Long opportunityId) {
        return activityLogRepository.findAll().stream()
                .filter(a -> a.getModule() == ActivityModule.OPPORTUNITY && opportunityId.equals(a.getEntityId()))
                .toList();
    }

    private OpportunityRequest edit(Opportunity o, String stage) {

        OpportunityRequest r = new OpportunityRequest();
        r.setTitle(o.getTitle());
        r.setProductValue(o.getProductValue());
        r.setExpectedClosingDate(o.getExpectedClosingDate());
        r.setSalesStage(stage);
        r.setLeadValidity(LeadValidity.VALID);
        r.setNextActionDescription("Call to discuss the proposal");
        r.setNextFollowUpDate(BusinessClock.now().plusDays(7));
        r.setNextActionActivityTypeId(activityTypeRepository.save(
                com.compact.crm.entity.ActivityType.builder().name("Call " + System.nanoTime()).build()).getId());
        return r;
    }

    @Test
    void newOpportunities_becomeInProgress_markedPreviouslyNew_withEveryOtherFieldUntouched() {

        Opportunity wasNew = stored("WasNew", newStage);
        Opportunity alwaysInProgress = stored("AlwaysIP", stageOfType(SalesStageType.IN_PROGRESS));
        flushAndClear();

        Opportunity before = reload(wasNew.getId());
        flushAndClear();

        assertThat(newStageRetirement.retireNewStage()).isEqualTo(1);
        flushAndClear();

        Opportunity after = reload(wasNew.getId());

        assertThat(after.getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
        assertThat(after.isPreviouslyNew()).isTrue();
        assertThat(after.getMigratedFromNewAt()).isNotNull();

        // nothing else changed - not even Last Modified
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getTitle()).isEqualTo(before.getTitle());
        assertThat(after.getProductValue()).isEqualTo(before.getProductValue());
        assertThat(after.getExpectedClosingDate()).isEqualTo(before.getExpectedClosingDate());
        assertThat(after.getContactPerson()).isEqualTo(before.getContactPerson());
        assertThat(after.getContactPhone()).isEqualTo(before.getContactPhone());
        assertThat(after.getContactEmail()).isEqualTo(before.getContactEmail());
        assertThat(after.getLead().getId()).isEqualTo(before.getLead().getId());
        assertThat(after.getLead().getAssignedEmployee().getId()).isEqualTo(admin.getId());
        assertThat(after.getSubStatus()).isNull();
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());

        // always In Progress: not marked
        Opportunity untouched = reload(alwaysInProgress.getId());
        assertThat(untouched.isPreviouslyNew()).isFalse();
        assertThat(untouched.getMigratedFromNewAt()).isNull();
        assertThat(history(alwaysInProgress.getId())).isEmpty();

        // the move is in the opportunity's history, as a Stage change by System
        List<ActivityLog> log = history(wasNew.getId());
        assertThat(log).hasSize(1);
        ActivityLog entry = log.get(0);
        assertThat(entry.getAction()).isEqualTo(ActivityAction.UPDATE);
        assertThat(entry.getFieldName()).isEqualTo("salesStage");
        assertThat(entry.getOldValue()).isEqualTo("NEW");
        assertThat(entry.getNewValue()).isEqualTo(stageOfType(SalesStageType.IN_PROGRESS).getName());
        assertThat(entry.getEmployee()).isNull();
        assertThat(entry.getEmployeeName()).isEqualTo(NewStageRetirement.SYSTEM_ACTOR);

        // the NEW row is kept (history refers to it) but inactive
        SalesStage kept = salesStageRepository.findById(newStage.getId()).orElseThrow();
        assertThat(kept.getStageType()).isEqualTo(SalesStageType.NEW);
        assertThat(kept.getIsActive()).isFalse();
    }

    @Test
    void runningAgain_changesNothing_andStartupDoesNotBringNewBack() {

        Opportunity wasNew = stored("Twice", newStage);
        flushAndClear();

        newStageRetirement.retireNewStage();
        flushAndClear();

        LocalDateTime firstStamp = reload(wasNew.getId()).getMigratedFromNewAt();
        long stages = salesStageRepository.count();

        // a later startup: seeding, then retirement again
        salesStageService.ensureCoreStages();
        assertThat(newStageRetirement.retireNewStage()).isZero();
        flushAndClear();

        assertThat(reload(wasNew.getId()).getMigratedFromNewAt()).isEqualTo(firstStamp);
        assertThat(history(wasNew.getId())).hasSize(1);
        assertThat(salesStageRepository.count()).isEqualTo(stages);
        assertThat(salesStageRepository.findById(newStage.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(salesStageService.getAll()).noneMatch(s -> s.getStageType() == SalesStageType.NEW);
    }

    @Test
    void theIndicatorSurvivesLaterEditsAndStageChanges() {

        Opportunity wasNew = stored("Survives", newStage);
        flushAndClear();
        newStageRetirement.retireNewStage();
        flushAndClear();

        Opportunity o = reload(wasNew.getId());
        OpportunityRequest titleEdit = edit(o, stageOfType(SalesStageType.IN_PROGRESS).getName());
        titleEdit.setTitle("Renamed deal");
        opportunityService.updateOpportunity(o.getId(), titleEdit);
        flushAndClear();

        Opportunity edited = reload(o.getId());
        assertThat(edited.getTitle()).isEqualTo("Renamed deal");
        assertThat(edited.isPreviouslyNew()).isTrue();

        OpportunityRequest toPostponed = edit(edited, "POSTPONED");
        toPostponed.setTitle("Renamed deal");
        opportunityService.updateOpportunity(o.getId(), toPostponed);
        flushAndClear();

        Opportunity postponed = opportunityService.getOpportunityById(o.getId());
        assertThat(postponed.getSalesStage().getStageType()).isEqualTo(SalesStageType.POSTPONED);
        assertThat(postponed.isPreviouslyNew()).isTrue();
    }

    @Test
    void newCanNeverBeChosenAgain_orReactivated() {

        newStageRetirement.retireNewStage();
        flushAndClear();

        // conversion into NEW is refused, nothing is created
        Lead lead = lead("TryNew");
        OpportunityRequest convert = edit(Opportunity.builder().title("Try").productValue(1.0)
                .expectedClosingDate(BusinessClock.today().plusDays(30)).build(), "NEW");

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(), convert))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retired");
        assertThat(opportunityRepository.existsByLeadId(lead.getId())).isFalse();

        // moving an existing opportunity into NEW is refused
        Opportunity ip = stored("IpCo", stageOfType(SalesStageType.IN_PROGRESS));
        assertThatThrownBy(() -> opportunityService.updateOpportunity(ip.getId(), edit(ip, "NEW")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retired");
        assertThat(reload(ip.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);

        // an Admin cannot reactivate it
        assertThatThrownBy(() -> salesStageService.activate(newStage.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retired");
        assertThat(salesStageRepository.findById(newStage.getId()).orElseThrow().getIsActive()).isFalse();
    }

    @Test
    void aFreshDatabase_getsTheFiveMainStages_andNoNewStage() {

        salesStageRepository.deleteAll();
        salesStageRepository.flush();

        salesStageService.ensureCoreStages();
        newStageRetirement.retireNewStage();

        assertThat(salesStageRepository.findByStageType(SalesStageType.NEW)).isEmpty();
        assertThat(salesStageService.getAll()).extracting(SalesStage::getStageType).containsExactly(
                SalesStageType.IN_PROGRESS, SalesStageType.POSTPONED, SalesStageType.WON,
                SalesStageType.LOST, SalesStageType.LEGACY_DROPPED);
    }

    @Test
    void anOlderUntypedNewStage_isRecognised_thenRetired() {

        // an installation whose NEW row was never typed
        salesStageRepository.deleteAll();
        salesStageRepository.flush();
        SalesStage untyped = salesStageRepository.save(SalesStage.builder().name("New").build());
        salesStageService.ensureCoreStages();

        Opportunity wasNew = stored("Untyped", untyped);
        flushAndClear();

        assertThat(newStageRetirement.retireNewStage()).isEqualTo(1);
        flushAndClear();

        assertThat(reload(wasNew.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
        assertThat(reload(wasNew.getId()).isPreviouslyNew()).isTrue();
        assertThat(salesStageRepository.findById(untyped.getId()).orElseThrow().getStageType())
                .isEqualTo(SalesStageType.NEW);
    }

    @Test
    void aDuplicateUntypedNewStage_isRetiredToo_andMarkedPreviouslyNew() {

        // a second row named "New" that stayed untyped because the typed NEW exists
        SalesStage duplicate = salesStageRepository.save(SalesStage.builder().name("New").build());
        Opportunity typed = stored("TypedNew", newStage);
        Opportunity untyped = stored("UntypedNew", duplicate);
        flushAndClear();

        assertThat(newStageRetirement.retireNewStage()).isEqualTo(2);
        flushAndClear();

        for (Opportunity o : List.of(typed, untyped)) {
            assertThat(reload(o.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
            assertThat(reload(o.getId()).isPreviouslyNew()).isTrue();
            assertThat(history(o.getId())).hasSize(1);
        }

        assertThat(salesStageRepository.findById(duplicate.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(newStageRetirement.retireNewStage()).isZero();
    }

    @Test
    void withoutAnInProgressStage_nothingIsMovedOrGuessed() {

        salesStageRepository.findByStageType(SalesStageType.IN_PROGRESS).forEach(s -> {
            s.setStageType(null);
            s.setName("Not In Progress");
            salesStageRepository.save(s);
        });

        Opportunity wasNew = stored("NoTarget", newStage);
        flushAndClear();

        assertThat(newStageRetirement.retireNewStage()).isZero();
        flushAndClear();

        assertThat(reload(wasNew.getId()).getSalesStage().getId()).isEqualTo(newStage.getId());
        assertThat(reload(wasNew.getId()).isPreviouslyNew()).isFalse();
    }
}
