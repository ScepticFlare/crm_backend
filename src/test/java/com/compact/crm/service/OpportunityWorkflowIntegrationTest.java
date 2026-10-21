package com.compact.crm.service;

import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.request.ReturnToInProgressRequest;
import com.compact.crm.dto.response.WorkflowAlertsResponse;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.OpportunitySubStatus;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end checks (real services + real H2, no mocks) of the agreed
 * Opportunity workflow: mandatory closing date, IN PROGRESS sub-status,
 * LOST reason, optional POSTPONED review date with acknowledgement vs.
 * explicit return to IN PROGRESS, non-blocking warnings, field-level
 * history, and that historical records stay readable/editable.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, FollowUpService.class, CustomerService.class, SalesStageService.class,
        ActivityLogService.class, AccessControlService.class, CurrentUserService.class})
class OpportunityWorkflowIntegrationTest {

    @Autowired private OpportunityService opportunityService;
    @Autowired private SalesStageService salesStageService;
    @Autowired private FollowUpService followUpService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private SalesStageRepository salesStageRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Employee admin;
    private Industry industry;
    private LeadSourceMaster leadSource;
    private ActivityType activityType;

    private final Map<String, Permission> permissionsByCode = new HashMap<>();

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());

        grant(adminRole, LEAD_MANAGE);
        grant(adminRole, OPPORTUNITY_MANAGE);
        grant(adminRole, OPPORTUNITY_VIEW);
        grant(adminRole, com.compact.crm.security.AccessControlService.FOLLOWUP_MANAGE);

        admin = employeeRepository.save(Employee.builder()
                .name("Admin").email("admin@test.com").phone("9000000001").password("x").role(adminRole).build());

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());
        activityType = activityTypeRepository.save(ActivityType.builder().name("Call").build());

        // The stages the app seeds (SalesStageSeeder): core stages carry their
        // stable type; the two historical stages are typed legacy stages.
        salesStageService.ensureCoreStages();
        salesStageRepository.save(SalesStage.builder().name("QUOTATION SENT")
                .stageType(SalesStageType.LEGACY_QUOTATION_SENT).build());
        salesStageRepository.save(SalesStage.builder().name("NEGOTIATION")
                .stageType(SalesStageType.LEGACY_NEGOTIATION).build());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null, List.of()));
    }

    private void grant(Role role, String code) {

        Permission permission = permissionsByCode.computeIfAbsent(
                code, c -> permissionRepository.save(Permission.builder().code(c).build()));

        rolePermissionRepository.save(RolePermission.builder()
                .role(role).permission(permission).scope(Scope.ALL).build());
    }

    // A VALID lead WITHOUT any follow-up (what a brand-new lead looks like).
    private Lead saveLeadBare(String company) {

        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("Contact").phone("1234567890")
                .email(company.toLowerCase() + "@example.com")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.VALID)
                .assignedEmployee(admin).build());
    }

    // The follow-up that satisfies the mandatory-next-action rule: PENDING,
    // in the future, with a described next action.
    private FollowUp addQualifyingFollowUp(Lead lead) {

        return followUpRepository.save(FollowUp.builder()
                .lead(lead).employee(admin).activityType(activityType)
                .status(FollowUpStatus.PENDING)
                .scheduledDate(LocalDateTime.now().plusDays(10))
                .remarks("Call to confirm the requirement").build());
    }

    // A VALID lead that already has a qualifying next action, so converting it
    // (and later saving its opportunity) satisfies the mandatory rule.
    private Lead saveLead(String company) {

        Lead lead = saveLeadBare(company);
        addQualifyingFollowUp(lead);
        return lead;
    }

    // An active opportunity in the state OLD records are in: it exists, but
    // its next action was done/closed, so no QUALIFYING follow-up remains.
    private Opportunity convertBare(String company, String stage) {

        Opportunity o = convert(company, stage);

        for (FollowUp f : followUpRepository.findByLeadId(o.getLead().getId())) {
            f.setStatus(FollowUpStatus.COMPLETED);
            followUpRepository.save(f);
        }

        return o;
    }

    private OpportunityRequest request(String stage, LocalDate closing) {

        OpportunityRequest r = new OpportunityRequest();
        r.setTitle("Deal");
        r.setProductValue(1000.0);
        r.setExpectedClosingDate(closing);
        r.setSalesStage(stage);
        r.setLeadValidity(LeadValidity.VALID);
        return r;
    }

    private LocalDate future() {
        return BusinessClock.today().plusDays(30);
    }

    private Opportunity convert(String company, String stage) {
        return opportunityService.convertLeadToOpportunity(saveLead(company).getId(), request(stage, future()));
    }

    // A historical record: written straight to the repository the way old
    // code did - no closing date / sub-status / reasons.
    private Opportunity saveHistorical(String company, String stage, LocalDate closing) {

        Lead lead = saveLead(company);

        SalesStage s = salesStageRepository.findByNameIgnoreCase(stage).orElseThrow();

        return opportunityRepository.save(Opportunity.builder()
                .title(company + " Deal").productValue(500.0).expectedClosingDate(closing)
                .salesStage(s).leadValidity(LeadValidity.VALID).lead(lead).build());
    }

    private List<ActivityLog> opportunityHistory(Long id) {

        return activityLogRepository.findAll().stream()
                .filter(a -> a.getModule() == ActivityModule.OPPORTUNITY && id.equals(a.getEntityId()))
                .toList();
    }

    // ---------------------------------------------------------------- closing date

    @Test
    void convert_withoutClosingDate_isRejected_andNothingIsCreated() {

        Lead lead = saveLead("NoDate");

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(
                lead.getId(), request("IN PROGRESS", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected closing date is required");

        assertThat(opportunityRepository.existsByLeadId(lead.getId())).isFalse();
    }

    @Test
    void convert_withClosingDate_succeeds() {

        Opportunity o = convert("WithDate", "IN PROGRESS");

        assertThat(o.getId()).isNotNull();
        assertThat(o.getExpectedClosingDate()).isEqualTo(future());
        assertThat(o.getSalesStage().getName()).isEqualTo("IN PROGRESS");
    }

    @Test
    void update_activeOpportunity_cannotClearClosingDate() {

        Opportunity o = convert("ClearDate", "IN PROGRESS");

        assertThatThrownBy(() -> opportunityService.updateOpportunity(
                o.getId(), request("IN PROGRESS", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected closing date is required");

        assertThat(opportunityRepository.findById(o.getId()).orElseThrow().getExpectedClosingDate())
                .isEqualTo(future());
    }

    @Test
    void update_toTerminalStage_doesNotNeedClosingDate() {

        Opportunity o = convert("WonNoDate", "IN PROGRESS");

        Opportunity won = opportunityService.updateOpportunity(o.getId(), request("WON", null));

        assertThat(won.getSalesStage().getName()).isEqualTo("WON");
    }

    // ---------------------------------------------------------------- expiry warning

    @Test
    void expiredClosingDate_warnsButNeverChangesTheStage() {

        Opportunity o = convert("Expired", "IN PROGRESS");

        o.setExpectedClosingDate(BusinessClock.today().minusDays(3));
        opportunityRepository.save(o);

        Opportunity fetched = opportunityService.getOpportunityById(o.getId());

        assertThat(fetched.isClosingDateExpired()).isTrue();
        assertThat(fetched.getSalesStage().getName()).isEqualTo("IN PROGRESS");

        // Listing / alerts are read-only too.
        WorkflowAlertsResponse alerts = opportunityService.getWorkflowAlerts();
        assertThat(alerts.expiredClosingDateCount()).isEqualTo(1);
        assertThat(opportunityRepository.findById(o.getId()).orElseThrow().getSalesStage().getName())
                .isEqualTo("IN PROGRESS");
    }

    @Test
    void expiredClosingDate_isNotFlaggedForWonLostOrPostponed() {

        for (String stage : List.of("WON", "LOST", "POSTPONED")) {

            Opportunity o = saveHistorical("Closed " + stage, stage, BusinessClock.today().minusDays(10));

            assertThat(o.isClosingDateExpired()).as(stage).isFalse();
        }

        assertThat(opportunityService.getWorkflowAlerts().expiredClosingDateCount()).isZero();
    }

    @Test
    void futureOrTodayClosingDate_isNotExpired() {

        Opportunity today = saveHistorical("Today", "IN PROGRESS", BusinessClock.today());
        Opportunity later = saveHistorical("Later", "IN PROGRESS", future());

        assertThat(today.isClosingDateExpired()).isFalse();
        assertThat(later.isClosingDateExpired()).isFalse();
    }

    // ---------------------------------------------------------------- sub-status

    @Test
    void inProgress_canCarryEachSubStatus_andStaysInProgress() {

        Opportunity o = convert("Sub", "IN PROGRESS");

        for (OpportunitySubStatus sub : OpportunitySubStatus.values()) {

            OpportunityRequest r = request("IN PROGRESS", future());
            r.setSubStatus(sub);

            Opportunity updated = opportunityService.updateOpportunity(o.getId(), r);

            assertThat(updated.getSubStatus()).isEqualTo(sub);
            assertThat(updated.getSalesStage().getName()).isEqualTo("IN PROGRESS");
        }
    }

    @Test
    void subStatus_withAnyOtherStage_isRejected() {

        Opportunity o = convert("BadSub", "IN PROGRESS");

        for (String stage : List.of("NEW", "WON", "LOST", "POSTPONED")) {

            OpportunityRequest r = request(stage, future());
            r.setSubStatus(OpportunitySubStatus.NEGOTIATION);
            r.setLostReason("because");

            assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), r))
                    .as(stage)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only be set when the stage is In Progress");
        }
    }

    @Test
    void movingAwayFromInProgress_clearsTheSubStatus() {

        Opportunity o = convert("Clear", "IN PROGRESS");

        OpportunityRequest r = request("IN PROGRESS", future());
        r.setSubStatus(OpportunitySubStatus.QUOTATION_SENT);
        opportunityService.updateOpportunity(o.getId(), r);

        for (String stage : List.of("WON", "POSTPONED")) {

            // back to in-progress with a sub-status, then away again
            opportunityService.updateOpportunity(o.getId(), r);

            OpportunityRequest away = request(stage, future());
            Opportunity moved = opportunityService.updateOpportunity(o.getId(), away);

            assertThat(moved.getSubStatus()).as(stage).isNull();
            assertThat(moved.getEffectiveSubStatus()).as(stage).isNull();
        }
    }

    @Test
    void legacyQuotationSentAndNegotiationStages_cannotBeNewlySelected() {

        Opportunity o = convert("Legacy", "IN PROGRESS");

        for (String legacy : List.of("QUOTATION SENT", "NEGOTIATION")) {

            assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), request(legacy, future())))
                    .as(legacy)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sub-status of In Progress");
        }

        Lead lead = saveLead("LegacyConvert");

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(
                lead.getId(), request("NEGOTIATION", future())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historicalOpportunityInLegacyStage_staysReadable_andCanBeResavedUnchanged() {

        Opportunity old = saveHistorical("OldQuote", "QUOTATION SENT", future());

        // Reads as IN PROGRESS / QUOTATION_SENT without any stored sub-status.
        assertThat(old.getSubStatus()).isNull();
        assertThat(old.getEffectiveSubStatus()).isEqualTo(OpportunitySubStatus.QUOTATION_SENT);

        Opportunity resaved = opportunityService.updateOpportunity(old.getId(), request("QUOTATION SENT", future()));

        assertThat(resaved.getSalesStage().getName()).isEqualTo("QUOTATION SENT");

        // ...and can be moved onto the new model explicitly.
        OpportunityRequest migrate = request("IN PROGRESS", future());
        migrate.setSubStatus(OpportunitySubStatus.QUOTATION_SENT);

        Opportunity moved = opportunityService.updateOpportunity(old.getId(), migrate);

        assertThat(moved.getSalesStage().getName()).isEqualTo("IN PROGRESS");
        assertThat(moved.getSubStatus()).isEqualTo(OpportunitySubStatus.QUOTATION_SENT);
    }

    @Test
    void unknownStageName_isRejected_andNotCreated() {

        Opportunity o = convert("Unknown", "IN PROGRESS");
        long stagesBefore = salesStageRepository.count();

        assertThatThrownBy(() -> opportunityService.updateOpportunity(
                o.getId(), request("MADE UP STAGE", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");

        assertThat(salesStageRepository.count()).isEqualTo(stagesBefore);
    }

    // ---------------------------------------------------------------- LOST reason

    @Test
    void lost_requiresFreeTextReason() {

        Opportunity o = convert("Lost", "IN PROGRESS");

        for (String blank : new String[]{null, "", "   "}) {

            OpportunityRequest r = request("LOST", null);
            r.setLostReason(blank);

            assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), r))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reason");
        }

        assertThat(opportunityRepository.findById(o.getId()).orElseThrow().getSalesStage().getName())
                .isEqualTo("IN PROGRESS");

        OpportunityRequest ok = request("LOST", null);
        ok.setLostReason("  Customer chose a competitor on price  ");

        Opportunity lost = opportunityService.updateOpportunity(o.getId(), ok);

        assertThat(lost.getSalesStage().getName()).isEqualTo("LOST");
        assertThat(lost.getLostReason()).isEqualTo("Customer chose a competitor on price");
    }

    @Test
    void lostReason_isNotRequiredForOtherStages_andClearedWhenLeavingLost() {

        Opportunity o = convert("Reopen", "IN PROGRESS");

        // No reason demanded for other stages.
        opportunityService.updateOpportunity(o.getId(), request("POSTPONED", null));

        OpportunityRequest lostReq = request("LOST", null);
        lostReq.setLostReason("No budget");
        opportunityService.updateOpportunity(o.getId(), lostReq);

        Opportunity reopened = opportunityService.updateOpportunity(o.getId(), request("IN PROGRESS", future()));

        assertThat(reopened.getLostReason()).isNull();

        // ...but the earlier reason is still in the history.
        assertThat(opportunityHistory(o.getId()))
                .anyMatch(a -> "lostReason".equals(a.getFieldName())
                        && "No budget".equals(a.getNewValue()));
        assertThat(opportunityHistory(o.getId()))
                .anyMatch(a -> "lostReason".equals(a.getFieldName())
                        && "No budget".equals(a.getOldValue()) && a.getNewValue() == null);
    }

    @Test
    void historicalLostOpportunity_withoutReason_remainsReadableAndEditable() {

        Opportunity old = saveHistorical("OldLost", "LOST", null);

        assertThat(old.getLostReason()).isNull();
        assertThat(opportunityService.getOpportunityById(old.getId()).getLostReason()).isNull();

        // Editing something else must not demand an invented reason.
        OpportunityRequest edit = request("LOST", null);
        edit.setTitle("Renamed old deal");

        Opportunity saved = opportunityService.updateOpportunity(old.getId(), edit);

        assertThat(saved.getTitle()).isEqualTo("Renamed old deal");
        assertThat(saved.getLostReason()).isNull();
    }

    // ---------------------------------------------------------------- POSTPONED

    @Test
    void postponed_reviewDateIsOptional() {

        Opportunity o = convert("Postpone", "IN PROGRESS");

        Opportunity postponed = opportunityService.updateOpportunity(o.getId(), request("POSTPONED", null));

        assertThat(postponed.getSalesStage().getName()).isEqualTo("POSTPONED");
        assertThat(postponed.getPostponedReviewDate()).isNull();
        assertThat(postponed.isPostponedReminderDue()).isFalse();
    }

    @Test
    void postponedReminder_becomesDueOnReviewDate_acknowledgeKeepsTheStage_returnMovesIt() {

        Opportunity o = convert("Reminder", "IN PROGRESS");

        OpportunityRequest postpone = request("POSTPONED", null);
        postpone.setPostponedReviewDate(BusinessClock.today());

        Opportunity postponed = opportunityService.updateOpportunity(o.getId(), postpone);

        assertThat(postponed.isPostponedReminderDue()).isTrue();
        assertThat(opportunityService.getWorkflowAlerts().postponedReminderCount()).isEqualTo(1);

        // OK / acknowledge: reminder goes away, stage and date unchanged.
        Opportunity acked = opportunityService.acknowledgePostponedReminder(o.getId());

        assertThat(acked.isPostponedReminderDue()).isFalse();
        assertThat(acked.getSalesStage().getName()).isEqualTo("POSTPONED");
        assertThat(acked.getPostponedReviewDate()).isEqualTo(BusinessClock.today());
        assertThat(opportunityService.getWorkflowAlerts().postponedReminderCount()).isZero();

        // Persisted: a fresh load (as after a page refresh) is still acknowledged.
        Opportunity reloaded = opportunityRepository.findById(o.getId()).orElseThrow();
        assertThat(reloaded.getPostponedReminderAcknowledgedFor()).isEqualTo(BusinessClock.today());
        assertThat(reloaded.isPostponedReminderDue()).isFalse();
        assertThat(reloaded.getSalesStage().getName()).isEqualTo("POSTPONED");

        // Acknowledging again is harmless.
        assertThat(opportunityService.acknowledgePostponedReminder(o.getId()).getSalesStage().getName())
                .isEqualTo("POSTPONED");

        // The explicit, separate action changes the stage.
        ReturnToInProgressRequest ret = new ReturnToInProgressRequest();
        ret.setExpectedClosingDate(future());

        Opportunity resumed = opportunityService.returnToInProgress(o.getId(), ret);

        assertThat(resumed.getSalesStage().getName()).isEqualTo("IN PROGRESS");
        assertThat(resumed.getExpectedClosingDate()).isEqualTo(future());
        assertThat(resumed.getPostponedReviewDate()).isNull();
        assertThat(resumed.isPostponedReminderDue()).isFalse();
    }

    @Test
    void changingTheReviewDate_rearmsAnAcknowledgedReminder() {

        Opportunity o = convert("Rearm", "IN PROGRESS");

        OpportunityRequest postpone = request("POSTPONED", null);
        postpone.setPostponedReviewDate(BusinessClock.today().minusDays(2));
        opportunityService.updateOpportunity(o.getId(), postpone);
        opportunityService.acknowledgePostponedReminder(o.getId());

        OpportunityRequest later = request("POSTPONED", null);
        later.setPostponedReviewDate(BusinessClock.today().minusDays(1));

        assertThat(opportunityService.updateOpportunity(o.getId(), later).isPostponedReminderDue()).isTrue();
    }

    @Test
    void futureReviewDate_isNotYetDue() {

        Opportunity o = convert("FutureReview", "IN PROGRESS");

        OpportunityRequest postpone = request("POSTPONED", null);
        postpone.setPostponedReviewDate(future());

        assertThat(opportunityService.updateOpportunity(o.getId(), postpone).isPostponedReminderDue()).isFalse();
    }

    @Test
    void acknowledgeAndReturn_areRejectedForNonPostponedOpportunities() {

        Opportunity o = convert("NotPostponed", "IN PROGRESS");

        assertThatThrownBy(() -> opportunityService.acknowledgePostponedReminder(o.getId()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> opportunityService.returnToInProgress(o.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnToInProgress_needsAClosingDate_whenNoneExists() {

        Opportunity old = saveHistorical("OldPostponed", "POSTPONED", null);

        assertThatThrownBy(() -> opportunityService.returnToInProgress(old.getId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected closing date is required");

        assertThat(opportunityRepository.findById(old.getId()).orElseThrow().getSalesStage().getName())
                .isEqualTo("POSTPONED");
    }

    // ---------------------------------------------------------------- the missing-follow-up flag (state of older records)

    @Test
    void activeOpportunityWithoutAQualifyingFollowUp_isFlagged_andCountedInTheAlerts() {

        // An old record: active, but its only next action was completed.
        Opportunity o = convertBare("NoFollowUp", "IN PROGRESS");

        Opportunity loaded = opportunityService.getOpportunityById(o.getId());

        assertThat(loaded.getNoNextAction()).isTrue();
        assertThat(loaded.getHasNextAction()).isFalse();
        assertThat(opportunityService.getWorkflowAlerts().noNextActionCount()).isEqualTo(1);
    }

    @Test
    void addingAQualifyingFollowUp_clearsTheFlag() {

        Opportunity o = convertBare("HasFollowUp", "IN PROGRESS");

        followUpRepository.save(FollowUp.builder()
                .opportunity(o).employee(admin).activityType(activityType)
                .status(FollowUpStatus.PENDING)
                .scheduledDate(LocalDateTime.now().plusDays(2))
                .remarks("Send the revised quotation").build());

        Opportunity loaded = opportunityService.getOpportunityById(o.getId());

        assertThat(loaded.getNoNextAction()).isFalse();
        assertThat(loaded.getHasNextAction()).isTrue();
        assertThat(opportunityService.getWorkflowAlerts().noNextActionCount()).isZero();
    }

    @Test
    void followUpOnTheOriginatingLead_countsAsTheNextAction() {

        Lead lead = saveLeadBare("LeadFollowUp");
        addQualifyingFollowUp(lead);

        Opportunity o = opportunityService.convertLeadToOpportunity(lead.getId(), request("IN PROGRESS", future()));

        assertThat(o.getNoNextAction()).isFalse();
    }

    @Test
    void completedOverdueOrUndescribedFollowUps_doNotCountAsANextAction() {

        Opportunity o = convertBare("StaleFollowUp", "IN PROGRESS");

        // completed, even though in the future
        followUpRepository.save(FollowUp.builder()
                .opportunity(o).employee(admin).activityType(activityType)
                .status(FollowUpStatus.COMPLETED)
                .scheduledDate(LocalDateTime.now().plusDays(2)).remarks("Already done").build());

        // pending but overdue
        followUpRepository.save(FollowUp.builder()
                .opportunity(o).employee(admin).activityType(activityType)
                .status(FollowUpStatus.PENDING)
                .scheduledDate(LocalDateTime.now().minusDays(2)).remarks("Missed call").build());

        // pending and upcoming but with no description of the next action
        followUpRepository.save(FollowUp.builder()
                .opportunity(o).employee(admin).activityType(activityType)
                .status(FollowUpStatus.PENDING)
                .scheduledDate(LocalDateTime.now().plusDays(2)).build());

        // cancelled
        followUpRepository.save(FollowUp.builder()
                .opportunity(o).employee(admin).activityType(activityType)
                .status(FollowUpStatus.CANCELLED)
                .scheduledDate(LocalDateTime.now().plusDays(2)).remarks("Cancelled visit").build());

        assertThat(opportunityService.getOpportunityById(o.getId()).getNoNextAction()).isTrue();
    }

    @Test
    void closedOpportunities_areNeverFlaggedForMissingFollowUp() {

        Opportunity won = saveHistorical("WonDeal", "WON", null);

        assertThat(opportunityService.getOpportunityById(won.getId()).getNoNextAction()).isFalse();
        assertThat(opportunityService.getWorkflowAlerts().noNextActionCount()).isZero();
    }

    // ---------------------------------------------------------------- history

    @Test
    void history_recordsFieldOldNewValueActorAndTimestamp() {

        Opportunity o = convert("History", "IN PROGRESS");

        LocalDate newDate = future().plusDays(10);

        OpportunityRequest edit = request("IN PROGRESS", newDate);
        edit.setClosingDateChangeReason("Waiting on the customer");
        edit.setSubStatus(OpportunitySubStatus.QUOTATION_SENT);

        LocalDateTime before = LocalDateTime.now().minusSeconds(5);

        opportunityService.updateOpportunity(o.getId(), edit);

        List<ActivityLog> history = opportunityHistory(o.getId());

        ActivityLog dateChange = history.stream()
                .filter(a -> "expectedClosingDate".equals(a.getFieldName())).findFirst().orElseThrow();

        assertThat(dateChange.getOldValue()).isEqualTo(future().toString());
        assertThat(dateChange.getNewValue()).isEqualTo(newDate.toString());
        assertThat(dateChange.getEmployeeName()).isEqualTo("Admin");
        assertThat(dateChange.getEmployeeId()).isEqualTo(admin.getId());
        assertThat(dateChange.getCreatedAt()).isAfter(before);
        assertThat(dateChange.getDescription()).contains("Expected closing date");

        ActivityLog sub = history.stream()
                .filter(a -> "subStatus".equals(a.getFieldName())).findFirst().orElseThrow();

        assertThat(sub.getOldValue()).isNull();
        assertThat(sub.getNewValue()).isEqualTo("QUOTATION_SENT");
    }

    @Test
    void history_recordsStageChangeAndSubStatusClearedOnMove() {

        Opportunity o = convert("StageHistory", "IN PROGRESS");

        OpportunityRequest sub = request("IN PROGRESS", future());
        sub.setSubStatus(OpportunitySubStatus.NEGOTIATION);
        opportunityService.updateOpportunity(o.getId(), sub);

        opportunityService.updateOpportunity(o.getId(), request("WON", future()));

        List<ActivityLog> history = opportunityHistory(o.getId());

        assertThat(history).anyMatch(a -> "salesStage".equals(a.getFieldName())
                && "IN PROGRESS".equals(a.getOldValue()) && "WON".equals(a.getNewValue()));

        assertThat(history).anyMatch(a -> "subStatus".equals(a.getFieldName())
                && "NEGOTIATION".equals(a.getOldValue()) && a.getNewValue() == null);
    }

    @Test
    void history_recordsAcknowledgementAndReturnToInProgress() {

        Opportunity o = convert("AckHistory", "IN PROGRESS");

        OpportunityRequest postpone = request("POSTPONED", null);
        postpone.setPostponedReviewDate(BusinessClock.today());
        opportunityService.updateOpportunity(o.getId(), postpone);

        opportunityService.acknowledgePostponedReminder(o.getId());

        ReturnToInProgressRequest ret = new ReturnToInProgressRequest();
        ret.setExpectedClosingDate(future());
        opportunityService.returnToInProgress(o.getId(), ret);

        List<ActivityLog> history = opportunityHistory(o.getId());

        assertThat(history).anyMatch(a -> "postponedReviewDate".equals(a.getFieldName())
                && a.getOldValue() == null && BusinessClock.today().toString().equals(a.getNewValue()));

        assertThat(history).anyMatch(a -> "postponedReminderAcknowledgedFor".equals(a.getFieldName())
                && a.getOldValue() == null && BusinessClock.today().toString().equals(a.getNewValue()));

        assertThat(history).anyMatch(a -> "salesStage".equals(a.getFieldName())
                && "POSTPONED".equals(a.getOldValue()) && "IN PROGRESS".equals(a.getNewValue()));
    }

    @Test
    void history_isNotWrittenWhenTheChangeIsRejected() {

        Opportunity o = convert("RejectedHistory", "IN PROGRESS");

        long before = opportunityHistory(o.getId()).size();

        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), request("LOST", null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(opportunityHistory(o.getId())).hasSize((int) before);
    }

    @Test
    void history_unchangedSave_keepsTheGenericUpdatedEntry() {

        Opportunity o = convert("NoOp", "IN PROGRESS");

        opportunityService.updateOpportunity(o.getId(), request("IN PROGRESS", future()));

        assertThat(opportunityHistory(o.getId()))
                .anyMatch(a -> "Updated opportunity".equals(a.getDescription()) && a.getFieldName() == null);
    }

    // ---------------------------------------------------------------- workflow alerts

    @Test
    void workflowAlerts_countEachCondition() {

        // expired + no qualifying follow-up (an old record)
        Opportunity expired = convertBare("A", "IN PROGRESS");
        expired.setExpectedClosingDate(BusinessClock.today().minusDays(1));
        opportunityRepository.save(expired);

        // fine: future date + a qualifying follow-up
        convert("B", "IN PROGRESS");

        WorkflowAlertsResponse alerts = opportunityService.getWorkflowAlerts();

        assertThat(alerts.expiredClosingDateCount()).isEqualTo(1);
        assertThat(alerts.expiredClosingDate()).extracting(WorkflowAlertsResponse.Item::id)
                .containsExactly(expired.getId());
        assertThat(alerts.noNextActionCount()).isEqualTo(1);
        assertThat(alerts.noNextAction()).extracting(WorkflowAlertsResponse.Item::id)
                .containsExactly(expired.getId());
        assertThat(alerts.postponedReminderCount()).isZero();
    }

    // ---------------------------------------------------------------- closing date change reason

    @Test
    void changingAnExistingClosingDateOfAnActiveOpportunity_needsAReason_andItIsKeptInTheHistory() {

        Opportunity o = convert("DateReason", "IN PROGRESS");
        long historyBefore = opportunityHistory(o.getId()).size();

        LocalDate later = future().plusDays(14);

        // no reason -> rejected, nothing changes, no history
        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), request("IN PROGRESS", later)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason for changing the expected closing date");

        // whitespace-only reason is no reason either
        OpportunityRequest blank = request("IN PROGRESS", later);
        blank.setClosingDateChangeReason("   ");
        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), blank))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(opportunityRepository.findById(o.getId()).orElseThrow().getExpectedClosingDate()).isEqualTo(future());
        assertThat(opportunityHistory(o.getId())).hasSize((int) historyBefore);

        OpportunityRequest ok = request("IN PROGRESS", later);
        ok.setClosingDateChangeReason("Customer's board meets later");
        opportunityService.updateOpportunity(o.getId(), ok);

        List<ActivityLog> history = opportunityHistory(o.getId());

        assertThat(history).anyMatch(a -> "expectedClosingDate".equals(a.getFieldName())
                && future().toString().equals(a.getOldValue()) && later.toString().equals(a.getNewValue()));
        assertThat(history).anyMatch(a -> "closingDateChangeReason".equals(a.getFieldName())
                && "Customer's board meets later".equals(a.getNewValue()) && "Admin".equals(a.getEmployeeName()));
    }

    @Test
    void closingDateReason_isNotAskedForTheSameDate_forTerminalStages_orForALegacyRecordWithoutADate() {

        Opportunity o = convert("NoReasonNeeded", "IN PROGRESS");

        // same date -> nothing to explain
        assertThat(opportunityService.updateOpportunity(o.getId(), request("IN PROGRESS", future())).getId()).isNotNull();

        // moving to WON/LOST/POSTPONED with another date is not an "active" slip
        OpportunityRequest won = request("WON", future().plusDays(3));
        assertThat(opportunityService.updateOpportunity(o.getId(), won).getSalesStage().getName()).isEqualTo("WON");

        // a legacy record with no date can be given its first date without a reason
        Opportunity legacy = saveHistorical("LegacyFirstDate", "IN PROGRESS", null);
        assertThat(opportunityService.updateOpportunity(legacy.getId(), request("IN PROGRESS", future()))
                .getExpectedClosingDate()).isEqualTo(future());
    }

    // ---------------------------------------------------------------- postponed reason (review date stays optional)

    @Test
    void postponedReason_isOptional_storedAndShownInHistory_andClearedWhenResumed() {

        Opportunity o = convert("PostponedReason", "IN PROGRESS");

        // neither reason nor review date is required
        Opportunity bare = opportunityService.updateOpportunity(o.getId(), request("POSTPONED", null));
        assertThat(bare.getPostponedReason()).isNull();
        assertThat(bare.getPostponedReviewDate()).isNull();

        OpportunityRequest withReason = request("POSTPONED", null);
        withReason.setPostponedReason("  Customer froze capex until Q3  ");
        Opportunity saved = opportunityService.updateOpportunity(o.getId(), withReason);

        assertThat(saved.getPostponedReason()).isEqualTo("Customer froze capex until Q3");
        assertThat(opportunityHistory(o.getId())).anyMatch(a -> "postponedReason".equals(a.getFieldName())
                && a.getOldValue() == null && "Customer froze capex until Q3".equals(a.getNewValue()));

        // the reason belongs to POSTPONED only
        OpportunityRequest active = request("IN PROGRESS", future());
        active.setPostponedReason("should be ignored");
        assertThat(opportunityService.updateOpportunity(o.getId(), active).getPostponedReason()).isNull();
    }

    @Test
    void returnToInProgress_clearsThePostponedReason() {

        Opportunity o = convert("ResumeReason", "IN PROGRESS");

        OpportunityRequest p = request("POSTPONED", null);
        p.setPostponedReason("Waiting for budget");
        opportunityService.updateOpportunity(o.getId(), p);

        ReturnToInProgressRequest ret = new ReturnToInProgressRequest();
        ret.setExpectedClosingDate(future());

        assertThat(opportunityService.returnToInProgress(o.getId(), ret).getPostponedReason()).isNull();
    }

    // ---------------------------------------------------------------- mandatory next action

    private OpportunityRequest withNextAction(OpportunityRequest r, String what, LocalDateTime when) {
        r.setNextActionDescription(what);
        r.setNextFollowUpDate(when);
        r.setNextActionActivityTypeId(activityType.getId());
        return r;
    }

    @Test
    void conversion_withoutAnyQualifyingFollowUp_isRejected_andNothingIsCreated() {

        Lead lead = saveLeadBare("NoNextAction");
        long opportunitiesBefore = opportunityRepository.count();

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(
                lead.getId(), request("IN PROGRESS", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a pending follow-up with a next action");

        assertThat(opportunityRepository.count()).isEqualTo(opportunitiesBefore);
        assertThat(followUpRepository.findByLeadId(lead.getId())).isEmpty();
    }

    @Test
    void conversion_withInlineNextAction_createsOpportunityAndFollowUpTogether() {

        Lead lead = saveLeadBare("InlineNext");
        LocalDateTime when = LocalDateTime.now().plusDays(5);

        Opportunity o = opportunityService.convertLeadToOpportunity(lead.getId(),
                withNextAction(request("IN PROGRESS", future()), "  Send the proposal  ", when));

        List<FollowUp> created = followUpRepository.findAll().stream()
                .filter(f -> f.getOpportunity() != null && f.getOpportunity().getId().equals(o.getId()))
                .toList();

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getStatus()).isEqualTo(FollowUpStatus.PENDING);
        assertThat(created.get(0).getRemarks()).isEqualTo("Send the proposal");
        assertThat(created.get(0).getScheduledDate()).isEqualTo(when);
        assertThat(opportunityService.getOpportunityById(o.getId()).getNoNextAction()).isFalse();
    }

    @Test
    void invalidInlineNextAction_isRejected_withoutCreatingAnything() {

        Lead lead = saveLeadBare("BadInline");
        long opportunitiesBefore = opportunityRepository.count();

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(),
                withNextAction(request("IN PROGRESS", future()), "x", LocalDateTime.now().plusDays(2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("describe the next action");

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(),
                withNextAction(request("IN PROGRESS", future()), "Call back", LocalDateTime.now().minusDays(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("now or in the future");

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(),
                withNextAction(request("IN PROGRESS", future()), "Call back", null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(opportunityRepository.count()).isEqualTo(opportunitiesBefore);
        assertThat(followUpRepository.findAll()).isEmpty();
    }

    @Test
    void terminalAndPostponedStages_doNotNeedANextAction() {

        for (String stage : List.of("WON", "POSTPONED")) {

            Lead lead = saveLeadBare("Exempt " + stage);

            Opportunity o = opportunityService.convertLeadToOpportunity(lead.getId(), request(stage, future()));

            assertThat(o.getSalesStage().getName()).isEqualTo(stage);
            assertThat(o.getNoNextAction()).isFalse();
        }

        Lead lost = saveLeadBare("Exempt LOST");
        OpportunityRequest r = request("LOST", future());
        r.setLostReason("Chose a competitor");

        assertThat(opportunityService.convertLeadToOpportunity(lost.getId(), r).getId()).isNotNull();
    }

    @Test
    void editingALegacyActiveOpportunityWithoutFollowUp_isRejected_untilOneIsSupplied() {

        Opportunity o = convertBare("LegacyActive", "IN PROGRESS");
        long followUpsBefore = followUpRepository.count();

        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), request("IN PROGRESS", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a pending follow-up");

        Opportunity fixed = opportunityService.updateOpportunity(o.getId(),
                withNextAction(request("IN PROGRESS", future()), "Follow up on the quote",
                        LocalDateTime.now().plusDays(3)));

        assertThat(fixed.getNoNextAction()).isFalse();
        assertThat(followUpRepository.count()).isEqualTo(followUpsBefore + 1);
    }

    @Test
    void legacyActiveOpportunityWithoutFollowUp_canStillBeClosedOrPostponed() {

        Opportunity won = convertBare("LegacyToWon", "IN PROGRESS");

        assertThat(opportunityService.updateOpportunity(won.getId(), request("WON", null))
                .getSalesStage().getName()).isEqualTo("WON");

        Opportunity postponed = convertBare("LegacyToPostponed", "IN PROGRESS");

        assertThat(opportunityService.updateOpportunity(postponed.getId(), request("POSTPONED", null))
                .getSalesStage().getName()).isEqualTo("POSTPONED");
    }

    @Test
    void legacyOpportunityStaysViewable_andFlaggedForTheFix() {

        Opportunity o = convertBare("ViewableLegacy", "IN PROGRESS");

        assertThat(opportunityService.getOpportunityById(o.getId()).getNoNextAction()).isTrue();
    }

    @Test
    void resumingAPostponedOpportunity_needsANextAction() {

        Opportunity o = convertBare("ResumeNeeds", "IN PROGRESS");
        opportunityService.updateOpportunity(o.getId(), request("POSTPONED", null));

        ReturnToInProgressRequest bare = new ReturnToInProgressRequest();
        bare.setExpectedClosingDate(future());

        assertThatThrownBy(() -> opportunityService.returnToInProgress(o.getId(), bare))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a pending follow-up");

        assertThat(opportunityRepository.findById(o.getId()).orElseThrow().getSalesStage().getName())
                .isEqualTo("POSTPONED");

        ReturnToInProgressRequest withAction = new ReturnToInProgressRequest();
        withAction.setExpectedClosingDate(future());
        withAction.setNextActionDescription("Re-open the discussion");
        withAction.setNextFollowUpDate(LocalDateTime.now().plusDays(4));
        withAction.setNextActionActivityTypeId(activityType.getId());

        Opportunity resumed = opportunityService.returnToInProgress(o.getId(), withAction);

        assertThat(resumed.getSalesStage().getName()).isEqualTo("IN PROGRESS");
        assertThat(resumed.getNoNextAction()).isFalse();
    }

    @Test
    void movingAPostponedOpportunityBackToActiveByEdit_needsANextAction() {

        Opportunity o = convertBare("EditResume", "IN PROGRESS");
        opportunityService.updateOpportunity(o.getId(), request("POSTPONED", null));

        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), request("IN PROGRESS", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a pending follow-up");
    }

    @Test
    void followUpOnAnOpportunity_needsADescribedNextAction() {

        Opportunity o = convert("DescribedOnly", "IN PROGRESS");

        FollowUpRequest bad = new FollowUpRequest();
        bad.setOpportunityId(o.getId());
        bad.setEmployeeId(admin.getId());
        bad.setActivityTypeId(activityType.getId());
        bad.setStatus(FollowUpStatus.PENDING);
        bad.setScheduledDate(LocalDateTime.now().plusDays(2));

        assertThatThrownBy(() -> followUpService.createFollowUp(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("describe the next action");

        bad.setRemarks("Visit the plant");

        assertThat(followUpService.createFollowUp(bad).getId()).isNotNull();
    }

}
