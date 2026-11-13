package com.compact.crm.service;

import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.search.OpportunitySearchCriteria;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.OpportunitySubStatus;
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
 * Retirement of every remaining non-main stage (service.LegacyStageRetirement),
 * on a real H2 database with the real services. Approved mapping:
 * UNRESPONSIVE -> DROPPED, INVALID -> LOST, old QUOTATION SENT / NEGOTIATION
 * stages -> IN PROGRESS with that sub-status, custom stages -> IN PROGRESS
 * (with a sub-status only when the name clearly means one).
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, FollowUpService.class, CustomerService.class, SalesStageService.class,
        ActivityLogService.class, AccessControlService.class, CurrentUserService.class, LegacyStageRetirement.class})
class LegacyStageRetirementIntegrationTest {

    @Autowired private LegacyStageRetirement legacyStageRetirement;
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

        salesStageService.ensureCoreStages();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null, List.of()));
    }

    // ---------------------------------------------------------------- helpers

    // A stage row as it already exists in an older database.
    private SalesStage existingStage(String name, SalesStageType type) {
        return salesStageRepository.saveAndFlush(SalesStage.builder().name(name).stageType(type).build());
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
                .lostReason("kept as it was").postponedReason("kept too")
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

    private List<Long> page(SalesStageType former) {
        OpportunitySearchCriteria c = new OpportunitySearchCriteria();
        c.setFormerStageType(former);
        return opportunityService.searchOpportunities(c, 0, 50, null, null).getContent().stream()
                .map(Opportunity::getId).toList();
    }

    private List<Long> stagePage(SalesStageType type) {
        OpportunitySearchCriteria c = new OpportunitySearchCriteria();
        c.setStageType(type);
        return opportunityService.searchOpportunities(c, 0, 50, null, null).getContent().stream()
                .map(Opportunity::getId).toList();
    }

    private OpportunityRequest edit(Opportunity o, String stage) {

        OpportunityRequest r = new OpportunityRequest();
        r.setTitle(o.getTitle());
        r.setProductValue(o.getProductValue());
        r.setExpectedClosingDate(BusinessClock.today().plusDays(30));
        r.setClosingDateChangeReason("Reopened after the stage retirement");
        r.setSalesStage(stage);
        r.setLeadValidity(LeadValidity.VALID);
        r.setNextActionDescription("Call to discuss the proposal");
        r.setNextFollowUpDate(BusinessClock.now().plusDays(7));
        r.setNextActionActivityTypeId(activityTypeRepository.save(
                ActivityType.builder().name("Call " + System.nanoTime()).build()).getId());
        return r;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void unresponsiveBecomesDropped_invalidBecomesLost_everyOtherFieldUntouched() {

        SalesStage unresponsive = existingStage("Gone Quiet", SalesStageType.LEGACY_UNRESPONSIVE);
        SalesStage invalid = existingStage("INVALID", SalesStageType.LEGACY_INVALID);

        Opportunity u = stored("Quiet", unresponsive);
        Opportunity i = stored("Bogus", invalid);
        Opportunity alreadyDropped = stored("Dropped", stageOfType(SalesStageType.LEGACY_DROPPED));
        flushAndClear();

        Opportunity before = reload(u.getId());
        flushAndClear();

        assertThat(legacyStageRetirement.retireLegacyStages()).isEqualTo(2);
        flushAndClear();

        Opportunity after = reload(u.getId());
        assertThat(after.getSalesStage().getStageType()).isEqualTo(SalesStageType.LEGACY_DROPPED);
        assertThat(after.getRetiredStageType()).isEqualTo(SalesStageType.LEGACY_UNRESPONSIVE);
        assertThat(after.getRetiredStageName()).isEqualTo("Gone Quiet");
        assertThat(after.getRetiredStageMovedAt()).isNotNull();

        // nothing else changed - not even Last Modified
        assertThat(after.getTitle()).isEqualTo(before.getTitle());
        assertThat(after.getProductValue()).isEqualTo(before.getProductValue());
        assertThat(after.getExpectedClosingDate()).isEqualTo(before.getExpectedClosingDate());
        assertThat(after.getContactPerson()).isEqualTo(before.getContactPerson());
        assertThat(after.getContactPhone()).isEqualTo(before.getContactPhone());
        assertThat(after.getContactEmail()).isEqualTo(before.getContactEmail());
        assertThat(after.getLostReason()).isEqualTo(before.getLostReason());
        assertThat(after.getPostponedReason()).isEqualTo(before.getPostponedReason());
        assertThat(after.getLead().getId()).isEqualTo(before.getLead().getId());
        assertThat(after.getLead().getAssignedEmployee().getId()).isEqualTo(admin.getId());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.isPreviouslyNew()).isFalse();

        Opportunity lost = reload(i.getId());
        assertThat(lost.getSalesStage().getStageType()).isEqualTo(SalesStageType.LOST);
        assertThat(lost.getRetiredStageType()).isEqualTo(SalesStageType.LEGACY_INVALID);
        assertThat(lost.getRetiredStageName()).isEqualTo("INVALID");

        // the always-Dropped one is not touched
        assertThat(reload(alreadyDropped.getId()).getRetiredStageMovedAt()).isNull();
        assertThat(history(alreadyDropped.getId())).isEmpty();

        // the move is in the history, by System, with the old stage name
        List<ActivityLog> log = history(u.getId());
        assertThat(log).hasSize(1);
        assertThat(log.get(0).getFieldName()).isEqualTo("salesStage");
        assertThat(log.get(0).getOldValue()).isEqualTo("Gone Quiet");
        assertThat(log.get(0).getNewValue()).isEqualTo(stageOfType(SalesStageType.LEGACY_DROPPED).getName());
        assertThat(log.get(0).getEmployee()).isNull();
        assertThat(log.get(0).getEmployeeName()).isEqualTo(LegacyStageRetirement.SYSTEM_ACTOR);

        // stage rows kept (history refers to them) but inactive
        assertThat(salesStageRepository.findById(unresponsive.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(salesStageRepository.findById(invalid.getId()).orElseThrow().getIsActive()).isFalse();

        // the Unresponsive / Invalid pages still list them, and so do Dropped / Lost
        assertThat(page(SalesStageType.LEGACY_UNRESPONSIVE)).containsExactly(u.getId());
        assertThat(page(SalesStageType.LEGACY_INVALID)).containsExactly(i.getId());
        assertThat(stagePage(SalesStageType.LEGACY_DROPPED)).containsExactlyInAnyOrder(u.getId(), alreadyDropped.getId());
        assertThat(stagePage(SalesStageType.LOST)).containsExactly(i.getId());
    }

    @Test
    void reopeningAMovedOpportunity_takesItOffTheUnresponsivePage_butKeepsItsProvenance() {

        Opportunity u = stored("Reopen", existingStage("UNRESPONSIVE", SalesStageType.LEGACY_UNRESPONSIVE));
        flushAndClear();
        legacyStageRetirement.retireLegacyStages();
        flushAndClear();

        opportunityService.updateOpportunity(u.getId(), edit(reload(u.getId()), "IN PROGRESS"));
        flushAndClear();

        Opportunity reopened = reload(u.getId());
        assertThat(reopened.getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
        assertThat(reopened.getRetiredStageType()).isEqualTo(SalesStageType.LEGACY_UNRESPONSIVE);
        assertThat(reopened.getRetiredStageName()).isEqualTo("UNRESPONSIVE");
        assertThat(page(SalesStageType.LEGACY_UNRESPONSIVE)).isEmpty();
    }

    @Test
    void oldQuotationAndNegotiationStages_becomeInProgressWithThatSubStatus() {

        Opportunity q = stored("Quoted", existingStage("QUOTATION SENT", SalesStageType.LEGACY_QUOTATION_SENT));
        Opportunity n = stored("Haggling", existingStage("NEGOTIATION", SalesStageType.LEGACY_NEGOTIATION));
        flushAndClear();

        assertThat(legacyStageRetirement.retireLegacyStages()).isEqualTo(2);
        flushAndClear();

        Opportunity quoted = reload(q.getId());
        assertThat(quoted.getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
        assertThat(quoted.getSubStatus()).isEqualTo(OpportunitySubStatus.QUOTATION_SENT);
        assertThat(quoted.getEffectiveSubStatus()).isEqualTo(OpportunitySubStatus.QUOTATION_SENT);
        assertThat(quoted.getRetiredStageType()).isEqualTo(SalesStageType.LEGACY_QUOTATION_SENT);

        Opportunity negotiating = reload(n.getId());
        assertThat(negotiating.getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
        assertThat(negotiating.getSubStatus()).isEqualTo(OpportunitySubStatus.NEGOTIATION);

        // stage and sub-status changes both in the history
        assertThat(history(q.getId())).extracting(ActivityLog::getFieldName)
                .containsExactlyInAnyOrder("salesStage", "subStatus");
    }

    @Test
    void customStages_becomeInProgress_withASubStatusOnlyWhenTheNameIsUnambiguous() {

        Opportunity quote = stored("QuoteCo", existingStage("Quote Sent", null));
        Opportunity nego = stored("NegoCo", existingStage("Under Negotiation", null));
        Opportunity survey = stored("SurveyCo", existingStage("Site Survey", null));
        Opportunity closedWon = stored("CwCo", existingStage("Closed Won", null));
        Opportunity mixed = stored("MixCo", existingStage("Quotation / Negotiation", null));
        flushAndClear();

        assertThat(legacyStageRetirement.retireLegacyStages()).isEqualTo(5);
        flushAndClear();

        assertThat(reload(quote.getId()).getSubStatus()).isEqualTo(OpportunitySubStatus.QUOTATION_SENT);
        assertThat(reload(nego.getId()).getSubStatus()).isEqualTo(OpportunitySubStatus.NEGOTIATION);

        // ambiguous: the conservative In Progress, never an outcome, no sub-status invented
        for (Opportunity o : List.of(survey, closedWon, mixed)) {
            Opportunity moved = reload(o.getId());
            assertThat(moved.getSalesStage().getStageType()).as(o.getTitle()).isEqualTo(SalesStageType.IN_PROGRESS);
            assertThat(moved.getSubStatus()).as(o.getTitle()).isNull();
            assertThat(history(o.getId())).as(o.getTitle()).hasSize(1);
        }

        // the original custom name is kept: in the provenance and the history
        Opportunity cw = reload(closedWon.getId());
        assertThat(cw.getRetiredStageType()).isNull();
        assertThat(cw.getRetiredStageName()).isEqualTo("Closed Won");
        assertThat(cw.getRetiredStageMovedAt()).isNotNull();
        assertThat(history(closedWon.getId()).get(0).getOldValue()).isEqualTo("Closed Won");

        // every opportunity is now in a main stage; custom rows kept, inactive
        assertThat(opportunityRepository.findAll()).allMatch(o -> OpportunityWorkflow.isMainStage(o.getSalesStage()));
        assertThat(salesStageRepository.findByStageTypeIsNull()).hasSize(5).allMatch(s -> !s.getIsActive());
    }

    @Test
    void aCustomStageNamedExactlyLikeAStage_goesWhereThatStageGoes_neverToAGuessedOutcome() {

        // duplicates that stayed untyped because the typed row already exists
        Opportunity won = stored("DupWon", existingStage("won", null));
        Opportunity lost = stored("DupLost", existingStage("Lost!", null));
        Opportunity unresp = stored("DupUnresp", existingStage("Unresponsive ", null));
        Opportunity invalid = stored("DupInvalid", existingStage("invalid", null));
        Opportunity quote = stored("DupQuote", existingStage("Quotation_Sent", null));
        Opportunity ip = stored("DupIp", existingStage("In-Progress", null));
        flushAndClear();

        assertThat(legacyStageRetirement.retireLegacyStages()).isEqualTo(6);
        flushAndClear();

        assertThat(reload(won.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.WON);
        assertThat(reload(lost.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.LOST);
        assertThat(reload(unresp.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.LEGACY_DROPPED);
        assertThat(reload(invalid.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.LOST);
        assertThat(reload(quote.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
        assertThat(reload(quote.getId()).getSubStatus()).isEqualTo(OpportunitySubStatus.QUOTATION_SENT);
        assertThat(reload(ip.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);

        // provenance: the original name; the retired type where it names one,
        // so the Unresponsive / Invalid pages still list them
        assertThat(reload(won.getId()).getRetiredStageName()).isEqualTo("won");
        assertThat(reload(won.getId()).getRetiredStageType()).isNull();
        assertThat(reload(unresp.getId()).getRetiredStageType()).isEqualTo(SalesStageType.LEGACY_UNRESPONSIVE);
        assertThat(page(SalesStageType.LEGACY_UNRESPONSIVE)).containsExactly(unresp.getId());
        assertThat(page(SalesStageType.LEGACY_INVALID)).containsExactly(invalid.getId());

        assertThat(opportunityRepository.findAll()).allMatch(o -> OpportunityWorkflow.isMainStage(o.getSalesStage()));
    }

    @Test
    void duplicateUntypedMainStageNames_areMergedIntoTheOldest_soEveryOpportunityEndsInAMainStage() {

        // an older database: no typed WON, two untyped rows both meaning Won
        salesStageRepository.findByStageType(SalesStageType.WON).forEach(s -> {
            s.setStageType(null);
            s.setName("Won");
            salesStageRepository.saveAndFlush(s);
        });
        SalesStage first = salesStageRepository.findByNameIgnoreCase("Won").orElseThrow();
        SalesStage second = existingStage("WON ", null);
        Opportunity a = stored("WonA", first);
        Opportunity b = stored("WonB", second);
        flushAndClear();

        salesStageService.ensureCoreStages();
        legacyStageRetirement.retireLegacyStages();
        flushAndClear();

        // the oldest row became the WON stage; the other was merged into it
        assertThat(salesStageRepository.findByStageType(SalesStageType.WON))
                .extracting(SalesStage::getId).containsExactly(first.getId());
        assertThat(reload(a.getId()).getSalesStage().getId()).isEqualTo(first.getId());
        assertThat(reload(a.getId()).getRetiredStageMovedAt()).isNull();
        assertThat(reload(b.getId()).getSalesStage().getId()).isEqualTo(first.getId());
        assertThat(reload(b.getId()).getRetiredStageName()).isEqualTo("WON ");
        assertThat(salesStageRepository.findById(second.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(salesStageService.getAll()).extracting(SalesStage::getStageType).contains(SalesStageType.WON);
    }

    @Test
    void anOpportunityWithNoStage_becomesInProgress_withHistory() {

        Opportunity none = stored("NoStage", null);
        flushAndClear();
        Opportunity before = reload(none.getId());
        flushAndClear();

        assertThat(legacyStageRetirement.retireLegacyStages()).isEqualTo(1);
        flushAndClear();

        Opportunity after = reload(none.getId());
        assertThat(after.getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
        assertThat(after.getRetiredStageMovedAt()).isNotNull();
        assertThat(after.getRetiredStageName()).isNull();
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getTitle()).isEqualTo(before.getTitle());
        assertThat(history(none.getId())).hasSize(1);
        assertThat(history(none.getId()).get(0).getOldValue()).isNull();

        assertThat(legacyStageRetirement.retireLegacyStages()).isZero();
    }

    @Test
    void aCustomStageNamedNew_isLeftForTheNewStageRetirement() {

        Opportunity o = stored("CustomNew", existingStage("New", null));
        flushAndClear();

        assertThat(legacyStageRetirement.retireLegacyStages()).isZero();
        flushAndClear();

        assertThat(reload(o.getId()).getSalesStage().getName()).isEqualTo("New");
    }

    @Test
    void runningAgain_changesNothing_andRetiredStagesCanNeverBeChosenOrReactivated() {

        SalesStage unresponsive = existingStage("UNRESPONSIVE", SalesStageType.LEGACY_UNRESPONSIVE);
        SalesStage custom = existingStage("Site Survey", null);
        Opportunity u = stored("Twice", unresponsive);
        flushAndClear();

        legacyStageRetirement.retireLegacyStages();
        flushAndClear();
        LocalDateTime firstStamp = reload(u.getId()).getRetiredStageMovedAt();
        long stages = salesStageRepository.count();

        // a later startup: seeding, then retirement again
        salesStageService.ensureCoreStages();
        assertThat(legacyStageRetirement.retireLegacyStages()).isZero();
        flushAndClear();

        assertThat(reload(u.getId()).getRetiredStageMovedAt()).isEqualTo(firstStamp);
        assertThat(history(u.getId())).hasSize(1);
        assertThat(salesStageRepository.count()).isEqualTo(stages);

        // only the five main stages are offered
        assertThat(salesStageService.getAll()).extracting(SalesStage::getStageType).containsExactly(
                SalesStageType.IN_PROGRESS, SalesStageType.POSTPONED, SalesStageType.WON,
                SalesStageType.LOST, SalesStageType.LEGACY_DROPPED);

        // an Admin cannot reactivate a retired or custom stage
        for (SalesStage s : List.of(unresponsive, custom)) {
            assertThatThrownBy(() -> salesStageService.activate(s.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retired");
            assertThat(salesStageRepository.findById(s.getId()).orElseThrow().getIsActive()).isFalse();
        }

        // nor can anything be moved into one
        Opportunity ip = stored("IpCo", stageOfType(SalesStageType.IN_PROGRESS));
        assertThatThrownBy(() -> opportunityService.updateOpportunity(ip.getId(), edit(ip, "UNRESPONSIVE")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> opportunityService.updateOpportunity(ip.getId(), edit(ip, "Site Survey")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutALostStage_invalidIsNotMoved_butTheOthersAre() {

        salesStageRepository.findByStageType(SalesStageType.LOST).forEach(s -> {
            s.setStageType(null);
            s.setName("Not Lost");
            salesStageRepository.save(s);
        });

        Opportunity i = stored("NoLost", existingStage("INVALID", SalesStageType.LEGACY_INVALID));
        Opportunity u = stored("StillMoves", existingStage("UNRESPONSIVE", SalesStageType.LEGACY_UNRESPONSIVE));
        flushAndClear();

        legacyStageRetirement.retireLegacyStages();
        flushAndClear();

        assertThat(reload(i.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.LEGACY_INVALID);
        assertThat(reload(i.getId()).getRetiredStageMovedAt()).isNull();
        assertThat(reload(u.getId()).getSalesStage().getStageType()).isEqualTo(SalesStageType.LEGACY_DROPPED);
    }
}
