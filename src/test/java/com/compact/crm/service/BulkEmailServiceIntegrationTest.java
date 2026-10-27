package com.compact.crm.service;

import com.compact.crm.dto.request.BulkEmailRequest;
import com.compact.crm.dto.request.EmailTemplateRequest;
import com.compact.crm.dto.response.BulkEmailPreviewResponse;
import com.compact.crm.dto.response.BulkEmailSendResponse;
import com.compact.crm.entity.EmailTemplate;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.EmailTemplateType;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.EmailBulkBatchRepository;
import com.compact.crm.repository.EmailSendLogRepository;
import com.compact.crm.repository.EmailSuppressionRepository;
import com.compact.crm.repository.EmailTemplateRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulk email on a real H2 database with the real services and a FAKE email
 * transport (BrevoEmailClient is mocked - no real email is ever sent).
 */
@DataJpaTest
@Import({BulkEmailService.class, EmailTemplateService.class, ActivityLogService.class,
        AccessControlService.class, CurrentUserService.class, EmailSuppressionService.class})
class BulkEmailServiceIntegrationTest {

    @MockitoBean private BrevoEmailClient brevo;

    @Autowired private BulkEmailService bulk;
    @MockitoSpyBean private EmailSuppressionService suppression;
    @Autowired private EmailSuppressionRepository suppressionRepository;
    @Autowired private EmailBulkBatchRepository batchRepository;
    @Autowired private EmailTemplateService templates;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private EmailTemplateRepository emailTemplateRepository;
    @Autowired private EmailSendLogRepository sendLogRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Employee admin;
    private Employee manager;
    private Employee empA;
    private Employee empB;

    private Lead a1;   // empA, a1@acme.test, contact Asha
    private Lead a2;   // empA, no email
    private Lead a3;   // empA, malformed email
    private Lead a4;   // empA, same address as a1 (different case)
    private Lead a5;   // empA, blank contact person
    private Lead a6;   // empA, a6@acme.test, contact Zed
    private Lead b1;   // empB

    private Opportunity oppA1;
    private Opportunity oppB1;

    private final Map<String, Permission> permissions = new HashMap<>();

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        for (String code : List.of(AccessControlService.EMAIL_SEND, AccessControlService.OPPORTUNITY_VIEW,
                AccessControlService.LEAD_VIEW)) {
            grant(adminRole, code, Scope.ALL);
            grant(managerRole, code, Scope.TEAM);
            grant(employeeRole, code, Scope.OWN);
        }

        // template management is admin-only (V6)
        grant(adminRole, AccessControlService.EMAIL_TEMPLATE_MANAGE, Scope.ALL);

        admin = employee("Admin", "adm@t.test", "9600000001", adminRole, null);
        manager = employee("Manager", "mgr@t.test", "9600000002", managerRole, null);
        empA = employee("Emp A", "ea@t.test", "9600000003", employeeRole, manager);
        empB = employee("Emp B", "eb@t.test", "9600000004", employeeRole, null);

        a1 = lead("Acme Ltd", "Asha", "a1@acme.test", empA);
        a2 = lead("No Mail Co", "Nina", null, empA);
        a3 = lead("Bad Mail Co", "Bob", "not-an-email", empA);
        a4 = lead("Acme Branch", "Asha B", "A1@Acme.TEST", empA);
        a5 = lead("Blank Contact Co", "  ", "a5@acme.test", empA);
        a6 = lead("Zed Corp", "Zed", "a6@acme.test", empA);
        b1 = lead("Other Owner Co", "Bela", "b1@other.test", empB);

        oppA1 = opportunityRepository.save(Opportunity.builder().title("Genset AMC 2027").lead(a1).build());
        oppB1 = opportunityRepository.save(Opportunity.builder().title("Secret Deal").lead(b1).build());

        // every send is accepted unless a test says otherwise
        when(brevo.trySend(any(), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));

        authenticateAs(empA);
    }

    // ============================================================ authorization

    @Test
    void employee_cannotSeeOrEmailARecordOutsideTheirScope_andIdsCannotBypassIt() {

        BulkEmailPreviewResponse preview = bulk.preview(request(BulkEmailTarget.LEAD,
                List.of(a1.getId(), b1.getId(), 999_999L), "S", "B"));

        assertThat(by(preview, a1).isEligible()).isTrue();

        // another owner's record and a non-existent id look exactly the same, and reveal nothing
        for (Long id : List.of(b1.getId(), 999_999L)) {
            BulkEmailPreviewResponse.Recipient r = byId(preview, id);
            assertThat(r.isEligible()).isFalse();
            assertThat(r.getExclusionCode()).isEqualTo("NOT_AVAILABLE");
            assertThat(r.getCompanyName()).isNull();
            assertThat(r.getEmail()).isNull();
        }

        BulkEmailSendResponse sent = sendNew(BulkEmailTarget.LEAD,
                List.of(b1.getId(), 999_999L), "Subject", "Body");

        assertThat(sent.getExcluded()).isEqualTo(2);
        verify(brevo, never()).trySend(any(), any(), any(), any());
        assertThat(sendLogRepository.count()).isZero();
    }

    @Test
    void manager_reachesTheirTeam_adminReachesEveryone() {

        authenticateAs(manager);
        assertThat(by(bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId(), b1.getId()), "S", "B")), a1)
                .isEligible()).isTrue();
        assertThat(by(bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId(), b1.getId()), "S", "B")), b1)
                .isEligible()).isFalse();

        authenticateAs(admin);
        BulkEmailPreviewResponse all = bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId(), b1.getId()), "S", "B"));
        assertThat(all.getEligibleCount()).isEqualTo(2);
    }

    @Test
    void opportunities_areEmailedViaTheirLead_andOwnershipIsTheLeadsOwner() {

        BulkEmailPreviewResponse preview = bulk.preview(request(BulkEmailTarget.OPPORTUNITY,
                List.of(oppA1.getId(), oppB1.getId()), "About {{opportunityTitle}}", "Dear {{contactPerson}}"));

        BulkEmailPreviewResponse.Recipient mine = byId(preview, oppA1.getId());
        assertThat(mine.isEligible()).isTrue();
        assertThat(mine.getEmail()).isEqualTo("a1@acme.test");
        assertThat(mine.getRenderedSubject()).isEqualTo("About Genset AMC 2027");

        assertThat(byId(preview, oppB1.getId()).isEligible()).isFalse();
        assertThat(byId(preview, oppB1.getId()).getExclusionCode()).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void anOpportunityPlaceholderIsFlaggedWhenEmailingLeads() {

        BulkEmailPreviewResponse preview = bulk.preview(request(BulkEmailTarget.LEAD,
                List.of(a1.getId()), "About {{opportunityTitle}}", "Hello"));

        assertThat(by(preview, a1).isEligible()).isFalse();
        assertThat(by(preview, a1).getExclusionCode()).isEqualTo("UNRESOLVED_PLACEHOLDER");
    }

    // ============================================================ recipient validation

    @Test
    void missingInvalidAndBlankPersonalisationAreExcludedWithReasons() {

        BulkEmailPreviewResponse preview = bulk.preview(request(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a2.getId(), a3.getId(), a5.getId()), "Hi", "Dear {{contactPerson}}"));

        assertThat(by(preview, a2).getExclusionCode()).isEqualTo("NO_EMAIL");
        assertThat(by(preview, a3).getExclusionCode()).isEqualTo("INVALID_EMAIL");
        assertThat(by(preview, a5).getExclusionCode()).isEqualTo("UNRESOLVED_PLACEHOLDER");
        assertThat(by(preview, a5).getExclusionMessage()).contains("{{contactPerson}} is empty");
        assertThat(preview.getEligibleCount()).isEqualTo(1);
        assertThat(preview.getExcludedCount()).isEqualTo(3);
    }

    @Test
    void duplicateAddresses_areReportedNotSilentlyDropped_andOnlyOneEmailGoes() {

        BulkEmailPreviewResponse preview = bulk.preview(request(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a4.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}"));

        assertThat(by(preview, a1).isEligible()).isTrue();
        assertThat(by(preview, a1).getWarnings()).anyMatch(w -> w.contains("share this address"));
        assertThat(by(preview, a4).isEligible()).isFalse();
        assertThat(by(preview, a4).getExclusionCode()).isEqualTo("DUPLICATE_EMAIL");
        assertThat(by(preview, a4).getExclusionMessage()).contains("Acme Ltd");

        BulkEmailSendResponse sent = sendNew(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a4.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}");

        assertThat(sent.getAccepted()).isEqualTo(2);
        assertThat(sent.getExcluded()).isEqualTo(1);
        verify(brevo, times(2)).trySend(any(), any(), any(), any());
    }

    @Test
    void sameAddressInAnotherChunkOfTheSameBatch_isNotEmailedTwice() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a4.getId()), "Hi", "Dear {{contactPerson}}");

        bulk.send(chunk(batch, List.of(a1.getId())));
        BulkEmailSendResponse second = bulk.send(chunk(batch, List.of(a4.getId())));

        assertThat(second.getResults().get(0).getOutcome()).isEqualTo("EXCLUDED");
        verify(brevo, times(1)).trySend(any(), any(), any(), any());
    }

    @Test
    void invalidLeadsAreWarnedAbout_notSilentlyExcluded() {

        a6.setLeadStatus(LeadStatus.INVALID);
        leadRepository.save(a6);

        BulkEmailPreviewResponse preview = bulk.preview(request(BulkEmailTarget.LEAD, List.of(a6.getId()), "S", "B"));

        assertThat(by(preview, a6).isEligible()).isTrue();
        assertThat(by(preview, a6).getWarnings()).anyMatch(w -> w.contains("INVALID"));
    }

    @Test
    void emptyOversizedAndMalformedRequestsAreRejected() {

        assertThatThrownBy(() -> bulk.preview(request(BulkEmailTarget.LEAD, List.of(), "S", "B")))
                .isInstanceOf(IllegalArgumentException.class);

        List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 1; i <= 11; i++) {
            tooMany.add(i);
        }

        assertThatThrownBy(() -> sendNew(BulkEmailTarget.LEAD, tooMany, "S", "B"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At most 10");

        assertThatThrownBy(() -> bulk.send(chunk("x", List.of(a1.getId()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch id");

        assertThatThrownBy(() -> bulk.createBatch(request(BulkEmailTarget.LEAD, List.of(a1.getId()), " ", "B")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> bulk.createBatch(request(BulkEmailTarget.LEAD, List.of(a1.getId()), "S", "")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> bulk.createBatch(request(null, List.of(a1.getId()), "S", "B")))
                .isInstanceOf(IllegalArgumentException.class);

        List<Long> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> bulk.preview(request(BulkEmailTarget.LEAD, withNull, "S", "B")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void aBatchWithNoEligibleRecipientsSendsNothing() {

        BulkEmailSendResponse sent = sendNew(BulkEmailTarget.LEAD,
                List.of(a2.getId(), a3.getId()), "Hi", "Body");

        assertThat(sent.getAccepted()).isZero();
        assertThat(sent.getExcluded()).isEqualTo(2);
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    // ============================================================ personalisation / templates

    @Test
    void eachRecipientReceivesOnlyTheirOwnPersonalisedMessage_asAnIndividualEmail() {

        sendNew(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()),
                "Hello {{contactPerson}} at {{companyName}}", "Dear {{contactPerson}}, about {{companyName}}.");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        verify(brevo, times(2)).trySend(to.capture(), subject.capture(), body.capture(), any());

        // one address per call - never a shared To/CC list
        assertThat(to.getAllValues()).containsExactly("a1@acme.test", "a6@acme.test");
        assertThat(subject.getAllValues()).containsExactly("Hello Asha at Acme Ltd", "Hello Zed at Zed Corp");
        assertThat(body.getAllValues().get(0)).isEqualTo("Dear Asha, about Acme Ltd.");
        assertThat(body.getAllValues().get(1)).isEqualTo("Dear Zed, about Zed Corp.");
        assertThat(body.getAllValues()).noneMatch(b -> b.contains("{{"));
    }

    @Test
    void unresolvedAndUnfinishedTemplateTextIsNeverSent() {

        BulkEmailSendResponse sent = sendNew(BulkEmailTarget.LEAD, List.of(a1.getId()),
                "Hi {{contactPerson}}", "Offer: [[ADMIN: describe the AMC offer here]] and {{nope}}");

        assertThat(sent.getExcluded()).isEqualTo(1);
        assertThat(sent.getResults().get(0).getDetail()).contains("Unknown placeholder {{nope}}").contains("[[...]]");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void editingTheBatchNeverChangesTheSharedTemplate() {

        authenticateAs(admin);
        EmailTemplate template = templates.create(new EmailTemplateRequest(
                "AMC Offer Test", "Shared subject {{contactPerson}}", "Shared body {{companyName}}",
                EmailTemplateType.AMC, false));

        authenticateAs(empA);

        BulkEmailRequest create = request(BulkEmailTarget.LEAD, List.of(a1.getId()),
                "My edited subject", "My edited body for {{companyName}}");
        create.setTemplateId(template.getId());

        bulk.send(chunk(bulk.createBatch(create).getBatchId(), List.of(a1.getId())));

        EmailTemplate reloaded = emailTemplateRepository.findById(template.getId()).orElseThrow();
        assertThat(reloaded.getSubject()).isEqualTo("Shared subject {{contactPerson}}");
        assertThat(reloaded.getBody()).isEqualTo("Shared body {{companyName}}");

        verify(brevo).trySend(eq("a1@acme.test"), eq("My edited subject"), eq("My edited body for Acme Ltd"), any());
    }

    @Test
    void anInactiveTemplateCannotBeUsedForABatch() {

        authenticateAs(admin);
        EmailTemplate template = templates.create(new EmailTemplateRequest(
                "Old", "S", "B", EmailTemplateType.KEEP_IN_TOUCH, false));
        templates.deactivate(template.getId());

        authenticateAs(empA);

        BulkEmailRequest request = request(BulkEmailTarget.LEAD, List.of(a1.getId()), "S", "B");
        request.setTemplateId(template.getId());

        assertThatThrownBy(() -> bulk.createBatch(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no longer active");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void employeesCanUseApprovedTemplates_butCannotCreateEditOrDeleteThem() {

        authenticateAs(admin);
        EmailTemplate template = templates.create(new EmailTemplateRequest(
                "Approved", "S", "B", EmailTemplateType.AMC, false));

        authenticateAs(empA);

        assertThat(templates.getById(template.getId()).getName()).isEqualTo("Approved");
        assertThat(templates.listByType(EmailTemplateType.AMC)).extracting(EmailTemplate::getName).contains("Approved");

        EmailTemplateRequest edit = new EmailTemplateRequest("Hacked", "S", "B", EmailTemplateType.AMC, false);

        assertThatThrownBy(() -> templates.create(edit)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.update(template.getId(), edit)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.deactivate(template.getId())).isInstanceOf(AccessDeniedException.class);

        assertThat(emailTemplateRepository.findById(template.getId()).orElseThrow().getName()).isEqualTo("Approved");
    }

    // ============================================================ outcomes / retries

    @Test
    void partialFailuresAreReportedPerRecipient() {

        when(brevo.trySend(eq("a6@acme.test"), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.FAILED, 400, "HTTP 400: invalid"));
        when(brevo.trySend(eq("a5@acme.test"), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.UNKNOWN, null, "timeout"));

        a5.setContactPerson("Eve");
        leadRepository.save(a5);

        BulkEmailSendResponse sent = sendNew(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a6.getId(), a5.getId(), a2.getId()), "Hi", "Dear {{contactPerson}}");

        assertThat(outcome(sent, a1)).isEqualTo("ACCEPTED");
        assertThat(outcome(sent, a6)).isEqualTo("FAILED");
        assertThat(outcome(sent, a5)).isEqualTo("UNKNOWN");
        assertThat(outcome(sent, a2)).isEqualTo("EXCLUDED");
        assertThat(sent.getAccepted()).isEqualTo(1);
        assertThat(sent.getFailed()).isEqualTo(1);
        assertThat(sent.getUnknown()).isEqualTo(1);
        assertThat(sent.getExcluded()).isEqualTo(1);
    }

    @Test
    void submittingTheSameChunkTwiceNeverSendsTwice() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}");
        BulkEmailRequest request = chunk(batch, List.of(a1.getId(), a6.getId()));

        BulkEmailSendResponse first = bulk.send(request);
        BulkEmailSendResponse second = bulk.send(request);

        assertThat(first.getAccepted()).isEqualTo(2);
        assertThat(second.getAccepted()).isEqualTo(2);
        assertThat(second.getResults()).allMatch(BulkEmailSendResponse.Result::isAlreadyProcessed);
        verify(brevo, times(2)).trySend(any(), any(), any(), any());   // 2 recipients, once each
    }

    @Test
    void aConfirmedFailureCanBeRetried_butAnAmbiguousOutcomeIsNeverResent() {

        when(brevo.trySend(eq("a6@acme.test"), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.FAILED, 429, "HTTP 429: rate limit"))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));
        when(brevo.trySend(eq("a1@acme.test"), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.UNKNOWN, null, "read timeout"));

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}");
        BulkEmailRequest request = chunk(batch, List.of(a1.getId(), a6.getId()));

        BulkEmailSendResponse first = bulk.send(request);
        assertThat(outcome(first, a6)).isEqualTo("FAILED");
        assertThat(outcome(first, a1)).isEqualTo("UNKNOWN");

        BulkEmailSendResponse retry = bulk.send(request);

        assertThat(outcome(retry, a6)).isEqualTo("ACCEPTED");   // confirmed failure -> safely retried
        assertThat(outcome(retry, a1)).isEqualTo("UNKNOWN");    // ambiguous -> NOT resent
        verify(brevo, times(1)).trySend(eq("a1@acme.test"), any(), any(), any());
        verify(brevo, times(2)).trySend(eq("a6@acme.test"), any(), any(), any());
    }

    @Test
    void anAbandonedPendingClaimIsReportedUnknown_notResent() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello");

        sendLogRepository.save(com.compact.crm.entity.EmailSendLog.builder()
                .batchId(batch).target(BulkEmailTarget.LEAD).targetId(a1.getId())
                .recipientEmail("a1@acme.test").recordLabel("Acme Ltd")
                .status(com.compact.crm.enums.EmailSendStatus.PENDING).sentByEmployeeId(empA.getId()).build());
        sendLogRepository.flush();

        // age the claim beyond the staleness window
        sendLogRepository.findAll().forEach(l -> {
            l.setUpdatedAt(java.time.LocalDateTime.now().minusMinutes(10));
            sendLogRepository.saveAndFlush(l);
        });

        BulkEmailSendResponse sent = bulk.send(chunk(batch, List.of(a1.getId())));

        assertThat(outcome(sent, a1)).isEqualTo("UNKNOWN");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void aFreshPendingClaimIsInProgress_notResent() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello");

        sendLogRepository.saveAndFlush(com.compact.crm.entity.EmailSendLog.builder()
                .batchId(batch).target(BulkEmailTarget.LEAD).targetId(a1.getId())
                .recipientEmail("a1@acme.test").status(com.compact.crm.enums.EmailSendStatus.PENDING)
                .sentByEmployeeId(empA.getId()).build());

        BulkEmailSendResponse sent = bulk.send(chunk(batch, List.of(a1.getId())));

        assertThat(outcome(sent, a1)).isEqualTo("IN_PROGRESS");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void permissionsAreRevalidatedAtSendTime() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello");

        // a record that was fine at review time is reassigned away before the send
        a1.setAssignedEmployee(empB);
        leadRepository.save(a1);

        BulkEmailSendResponse sent = bulk.send(chunk(batch, List.of(a1.getId())));

        assertThat(outcome(sent, a1)).isEqualTo("EXCLUDED");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void aBatchBelongsToItsSender() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello");
        bulk.send(chunk(batch, List.of(a1.getId())));

        authenticateAs(empB);

        assertThatThrownBy(() -> bulk.send(chunk(batch, List.of(a1.getId()))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bulk.results(batch)).isInstanceOf(AccessDeniedException.class);

        authenticateAs(empA);
        BulkEmailSendResponse results = bulk.results(batch);
        assertThat(results.getResults()).hasSize(1);
        assertThat(results.getAccepted()).isEqualTo(1);
    }

    @Test
    void onlyAcceptedSendsAreActivityLogged_withoutTheMessageText() {

        when(brevo.trySend(eq("a6@acme.test"), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.FAILED, 400, "HTTP 400"));

        sendNew(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "SECRET-BODY-TEXT");

        var entries = activityLogRepository.findAll().stream()
                .filter(a -> a.getAction() == ActivityAction.EMAIL_SENT).toList();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntityId()).isEqualTo(a1.getId());
        assertThat(entries.get(0).getDescription()).doesNotContain("SECRET-BODY-TEXT");
        assertThat(sendLogRepository.findAll()).allMatch(l -> !String.valueOf(l.getDetail()).contains("SECRET-BODY-TEXT"));
    }

    @Test
    void opportunityBatchesAreLoggedAgainstTheOpportunity() {

        sendNew(BulkEmailTarget.OPPORTUNITY, List.of(oppA1.getId()), "Hi", "Hello {{opportunityTitle}}");

        var entries = activityLogRepository.findAll().stream()
                .filter(a -> a.getAction() == ActivityAction.EMAIL_SENT).toList();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getModule()).isEqualTo(com.compact.crm.enums.ActivityModule.OPPORTUNITY);
        assertThat(entries.get(0).getEntityId()).isEqualTo(oppA1.getId());
    }

    // ============================================================ server-issued batches

    @Test
    void createdBatchesGetDistinctServerIssuedIds_andPinTheOwnerContentAndRecipients() {

        java.util.Set<String> seen = new java.util.HashSet<>();

        for (int i = 0; i < 25; i++) {
            assertThat(seen.add(newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()), "S", "B"))).isTrue();
        }

        var created = bulk.createBatch(request(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId(), a1.getId()), "Subj", "Body"));

        var stored = batchRepository.findByBatchId(created.getBatchId()).orElseThrow();
        assertThat(stored.getOwnerEmployeeId()).isEqualTo(empA.getId());
        assertThat(stored.recipientIdList()).containsExactly(a1.getId(), a6.getId());   // distinct, ordered
        assertThat(stored.getContentHash()).hasSize(64);
        assertThat(created.getRequested()).isEqualTo(2);
        assertThat(created.getChunkSize()).isEqualTo(10);
        assertThat(created.getMaxRecipients()).isEqualTo(100);
    }

    @Test
    void theRecipientCapAppliesToTheWholeBatch_notJustOneRequest() {

        List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            tooMany.add(i);
        }

        assertThatThrownBy(() -> bulk.createBatch(request(BulkEmailTarget.LEAD, tooMany, "S", "B")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At most 100");

        assertThat(batchRepository.count()).isZero();

        // exactly 100 is fine, and the chunks of it can never add up to more than 100
        assertThat(bulk.createBatch(request(BulkEmailTarget.LEAD, tooMany.subList(0, 100), "S", "B")).getRequested())
                .isEqualTo(100);
    }

    @Test
    void aBatchCanOnlySendItsOwnRecipients_withItsOwnContent() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Subj", "Body {{contactPerson}}");

        // a recipient that is not part of the batch
        assertThatThrownBy(() -> bulk.send(chunk(batch, List.of(a1.getId(), a5.getId()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not part of this batch");

        // changed message
        BulkEmailRequest changed = chunk(batch, List.of(a1.getId()));
        changed.setSubject("Other subject");
        changed.setBody("Body {{contactPerson}}");
        assertThatThrownBy(() -> bulk.send(changed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("differs");

        BulkEmailRequest onlySubject = chunk(batch, List.of(a1.getId()));
        onlySubject.setSubject("Subj");
        assertThatThrownBy(() -> bulk.send(onlySubject)).isInstanceOf(IllegalArgumentException.class);

        // changed target and template
        BulkEmailRequest otherTarget = chunk(batch, List.of(a1.getId()));
        otherTarget.setTarget(BulkEmailTarget.OPPORTUNITY);
        assertThatThrownBy(() -> bulk.send(otherTarget)).isInstanceOf(IllegalArgumentException.class);

        BulkEmailRequest otherTemplate = chunk(batch, List.of(a1.getId()));
        otherTemplate.setTemplateId(987_654L);
        assertThatThrownBy(() -> bulk.send(otherTemplate)).isInstanceOf(IllegalArgumentException.class);

        verify(brevo, never()).trySend(any(), any(), any(), any());

        // restating the SAME content is accepted, and the batch's stored message is what goes out
        BulkEmailRequest same = chunk(batch, List.of(a1.getId()));
        same.setTarget(BulkEmailTarget.LEAD);
        same.setSubject("Subj");
        same.setBody("Body {{contactPerson}}");

        assertThat(bulk.send(same).getAccepted()).isEqualTo(1);
        verify(brevo).trySend(eq("a1@acme.test"), eq("Subj"), eq("Body Asha"), any());
    }

    @Test
    void aChunkIsLimitedToTenRecords_evenInsideALargerBatch() {

        List<Long> twelve = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(lead("Chunk Co " + i, "P", "chunk" + i + "@acme.test", empA).getId());
        }

        String batch = newBatch(BulkEmailTarget.LEAD, twelve, "S", "B");

        assertThatThrownBy(() -> bulk.send(chunk(batch, twelve)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At most 10");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void aBatchIsUnusableByOthers_butReadableByAnAdmin_andUnknownIdsAre404() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Hello");
        bulk.send(chunk(batch, List.of(a1.getId())));

        for (Employee other : List.of(empB, manager)) {
            authenticateAs(other);
            assertThatThrownBy(() -> bulk.results(batch)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> bulk.send(chunk(batch, List.of(a6.getId())))).isInstanceOf(AccessDeniedException.class);
        }

        authenticateAs(admin);
        assertThat(bulk.results(batch).getResults()).hasSize(2);
        assertThatThrownBy(() -> bulk.send(chunk(batch, List.of(a6.getId())))).isInstanceOf(AccessDeniedException.class);

        authenticateAs(empA);
        assertThatThrownBy(() -> bulk.results("00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(com.compact.crm.exception.ResourceNotFoundException.class);

        verify(brevo, times(1)).trySend(any(), any(), any(), any());
    }

    // ============================================================ cross-batch duplicate protection

    @Test
    void theSameRecipientsUnderANewBatch_areNotEmailedAgain() {

        sendNew(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}");

        BulkEmailSendResponse again = sendNew(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}");

        assertThat(again.getAccepted()).isZero();
        assertThat(again.getExcluded()).isEqualTo(2);
        assertThat(again.getResults()).allMatch(r -> "RECENTLY_EMAILED".equals(r.getExclusionCode()));
        verify(brevo, times(2)).trySend(any(), any(), any(), any());      // the first batch only

        // the review step says so too, before anything is attempted
        BulkEmailPreviewResponse preview = bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId()), "S", "B"));
        assertThat(by(preview, a1).getExclusionCode()).isEqualTo("RECENTLY_EMAILED");
    }

    @Test
    void anUnknownOutcome_isNeverResentThroughANewBatch_orAnotherRecordWithTheSameAddress() {

        when(brevo.trySend(eq("a1@acme.test"), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.UNKNOWN, null, "read timeout"));

        assertThat(outcome(sendNew(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello"), a1)).isEqualTo("UNKNOWN");

        // a new batch for the same record
        BulkEmailSendResponse second = sendNew(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello");
        assertThat(outcome(second, a1)).isEqualTo("EXCLUDED");

        // another record that has the same address in a different case ("A1@Acme.TEST")
        BulkEmailSendResponse third = sendNew(BulkEmailTarget.LEAD, List.of(a4.getId()), "Hi", "Hello");
        assertThat(third.getResults().get(0).getExclusionCode()).isEqualTo("RECENTLY_EMAILED");

        // an opportunity of the same lead uses the same address
        BulkEmailSendResponse viaOpportunity = sendNew(BulkEmailTarget.OPPORTUNITY, List.of(oppA1.getId()), "Hi", "Hello");
        assertThat(viaOpportunity.getResults().get(0).getExclusionCode()).isEqualTo("RECENTLY_EMAILED");

        verify(brevo, times(1)).trySend(eq("a1@acme.test"), any(), any(), any());
    }

    @Test
    void anotherUsersRecentEmail_blocksTheSameAddress_withoutNamingThem() {

        sendNew(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello");

        authenticateAs(admin);
        BulkEmailSendResponse adminBatch = sendNew(BulkEmailTarget.LEAD, List.of(a4.getId()), "Hi", "Hello");

        BulkEmailSendResponse.Result r = adminBatch.getResults().get(0);
        assertThat(r.getExclusionCode()).isEqualTo("RECENTLY_EMAILED");
        assertThat(r.getDetail()).doesNotContain("Emp A").doesNotContain("ea@t.test");
        verify(brevo, times(1)).trySend(any(), any(), any(), any());
    }

    @Test
    void aConfirmedFailure_mayBeRetriedThroughANewBatch_andProtectionExpiresWithTheWindow() {

        when(brevo.trySend(eq("a6@acme.test"), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.FAILED, 400, "HTTP 400"))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));

        assertThat(outcome(sendNew(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Hello"), a6)).isEqualTo("FAILED");

        BulkEmailSendResponse retry = sendNew(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Hello");
        assertThat(outcome(retry, a6)).isEqualTo("ACCEPTED");                 // FAILED never blocks
        assertThat(outcome(retry, a1)).isEqualTo("EXCLUDED");                 // ACCEPTED does

        // after the de-duplication window the address may be emailed again
        sendLogRepository.findAll().forEach(l -> {
            l.setUpdatedAt(java.time.LocalDateTime.now().minusHours(25));
            sendLogRepository.saveAndFlush(l);
        });

        assertThat(outcome(sendNew(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello"), a1)).isEqualTo("ACCEPTED");
    }

    // ============================================================ replay + batch view

    @Test
    void replayReturnsTheRecordedOutcome_evenAfterTheLeadWasReassignedOrEdited() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}");
        BulkEmailRequest both = chunk(batch, List.of(a1.getId(), a6.getId()));

        assertThat(bulk.send(both).getAccepted()).isEqualTo(2);

        a1.setAssignedEmployee(empB);          // reassigned away from the sender
        leadRepository.save(a1);
        a6.setContactPerson("");               // data cleared
        a6.setEmail(null);
        leadRepository.save(a6);

        BulkEmailSendResponse replay = bulk.send(both);

        assertThat(replay.getAccepted()).isEqualTo(2);
        assertThat(replay.getExcluded()).isZero();
        assertThat(replay.getResults()).allMatch(r -> "ACCEPTED".equals(r.getOutcome()) && r.isAlreadyProcessed());

        // the batch view and the replay agree
        BulkEmailSendResponse view = bulk.results(batch);
        assertThat(view.getAccepted()).isEqualTo(2);
        assertThat(view.getResults()).extracting(BulkEmailSendResponse.Result::getOutcome).containsOnly("ACCEPTED");
        verify(brevo, times(2)).trySend(any(), any(), any(), any());
    }

    @Test
    void aRecipientWhoseRecordStopsQualifyingMidBatch_isExcludedForRealButNeverResent() {

        when(brevo.trySend(eq("a6@acme.test"), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.FAILED, 400, "HTTP 400"));

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId(), a5.getId()), "Hi", "Hello");

        bulk.send(chunk(batch, List.of(a1.getId(), a6.getId())));          // a1 ACCEPTED, a6 FAILED

        a6.setAssignedEmployee(empB);                                      // lost access before the retry
        leadRepository.save(a6);
        a5.setAssignedEmployee(empB);                                      // lost access before its chunk
        leadRepository.save(a5);

        BulkEmailSendResponse next = bulk.send(chunk(batch, List.of(a6.getId(), a5.getId())));

        assertThat(next.getResults()).extracting(BulkEmailSendResponse.Result::getExclusionCode)
                .containsExactly("NOT_AVAILABLE", "NOT_AVAILABLE");

        BulkEmailSendResponse view = bulk.results(batch);
        assertThat(view.getAccepted()).isEqualTo(1);
        assertThat(view.getFailed() + view.getExcluded()).isEqualTo(2);
        assertThat(view.getResults()).hasSize(3);
        verify(brevo, times(2)).trySend(any(), any(), any(), any());       // a1 and the one failed attempt of a6
    }

    @Test
    void revokingEmailSendMidBatch_stopsTheNextChunk() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Hello");
        bulk.send(chunk(batch, List.of(a1.getId())));

        rolePermissionRepository.findAll().stream()
                .filter(rp -> "EMPLOYEE".equals(rp.getRole().getName())
                        && AccessControlService.EMAIL_SEND.equals(rp.getPermission().getCode()))
                .forEach(rolePermissionRepository::delete);
        rolePermissionRepository.flush();

        assertThatThrownBy(() -> bulk.send(chunk(batch, List.of(a6.getId())))).isInstanceOf(AccessDeniedException.class);
        verify(brevo, times(1)).trySend(any(), any(), any(), any());
    }

    @Test
    void theBatchViewListsEverySelectedRecord_andTheCountsReconcile() {

        Lead extra = lead("Extra Co", "Ed", "ed@acme.test", empA);

        List<Long> ids = List.of(a1.getId(), a2.getId(), a3.getId(), a4.getId(), a5.getId(),
                a6.getId(), b1.getId(), 999_999L, extra.getId());

        String batch = newBatch(BulkEmailTarget.LEAD, ids, "Hi", "Dear {{contactPerson}}");

        bulk.send(chunk(batch, List.of(a1.getId(), a6.getId())));

        BulkEmailSendResponse view = bulk.results(batch);

        assertThat(view.getResults()).extracting(BulkEmailSendResponse.Result::getId).containsExactlyElementsOf(ids);

        assertThat(code(view, a2)).isEqualTo("NO_EMAIL");
        assertThat(code(view, a3)).isEqualTo("INVALID_EMAIL");
        assertThat(code(view, a4)).isEqualTo("DUPLICATE_EMAIL");
        assertThat(code(view, a5)).isEqualTo("UNRESOLVED_PLACEHOLDER");
        assertThat(view.getResults().stream().filter(r -> r.getId().equals(b1.getId())).findFirst().orElseThrow()
                .getExclusionCode()).isEqualTo("NOT_AVAILABLE");
        assertThat(outcome(view, a1)).isEqualTo("ACCEPTED");
        assertThat(outcome(view, extra)).isEqualTo("NOT_ATTEMPTED");

        assertThat(view.getAccepted()).isEqualTo(2);
        assertThat(view.getNotAttempted()).isEqualTo(1);
        assertThat(view.getExcluded()).isEqualTo(6);
        assertThat(view.getAccepted() + view.getFailed() + view.getUnknown() + view.getInProgress()
                + view.getExcluded() + view.getNotAttempted()).isEqualTo(ids.size());

        // a snapshot of the batch is not a replay: nothing in it is "already processed"
        assertThat(view.getResults()).noneMatch(BulkEmailSendResponse.Result::isAlreadyProcessed);

        // everything needed to resume after a refresh comes back from the server
        assertThat(view.getBatch().getRecipientIds()).containsExactlyElementsOf(ids);
        assertThat(view.getBatch().getSubject()).isEqualTo("Hi");
        assertThat(view.getBatch().getBody()).isEqualTo("Dear {{contactPerson}}");
    }

    // ============================================================ suppression

    @Test
    void aSuppressedAddress_isExcludedInPreviewAndSend_forEveryRecordSharingIt() {

        suppress("A1@ACME.test");                       // stored/compared normalized

        BulkEmailPreviewResponse preview = bulk.preview(request(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a4.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}"));

        assertThat(by(preview, a1).getExclusionCode()).isEqualTo("SUPPRESSED");
        assertThat(by(preview, a4).getExclusionCode()).isEqualTo("SUPPRESSED");     // shares the address
        assertThat(by(preview, a6).isEligible()).isTrue();
        assertThat(by(preview, a1).getExclusionMessage()).contains("do-not-email");

        BulkEmailSendResponse sent = sendNew(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a4.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}");

        assertThat(sent.getAccepted()).isEqualTo(1);
        assertThat(sent.getResults().stream().filter(r -> "SUPPRESSED".equals(r.getExclusionCode())).count()).isEqualTo(2);
        verify(brevo, times(1)).trySend(any(), any(), any(), any());
        verify(brevo, never()).trySend(eq("a1@acme.test"), any(), any(), any());

        // opportunities of the suppressed lead are blocked as well
        assertThat(sendNew(BulkEmailTarget.OPPORTUNITY, List.of(oppA1.getId()), "Hi", "Hello")
                .getResults().get(0).getExclusionCode()).isEqualTo("SUPPRESSED");
    }

    @Test
    void aSuppressionAddedAfterTheBatchWasCreated_stillWinsAtSendTime() {

        String batch = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Hello");

        suppress("a1@acme.test");

        BulkEmailSendResponse sent = bulk.send(chunk(batch, List.of(a1.getId(), a6.getId())));

        assertThat(outcome(sent, a1)).isEqualTo("EXCLUDED");
        assertThat(outcome(sent, a6)).isEqualTo("ACCEPTED");
        verify(brevo, never()).trySend(eq("a1@acme.test"), any(), any(), any());
    }

    @Test
    void suppressionIsCheckedAgainImmediatelyBeforeTheProviderCall() {

        // Evaluation is made to MISS the suppression (as if it were added a moment later),
        // so only the final check can stop the send.
        org.mockito.Mockito.doReturn(java.util.Set.of()).when(suppression).findSuppressed(any());
        suppress("a1@acme.test");

        BulkEmailSendResponse sent = sendNew(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello");

        assertThat(sent.getResults().get(0).getExclusionCode()).isEqualTo("SUPPRESSED");
        verify(brevo, never()).trySend(any(), any(), any(), any());
        assertThat(sendLogRepository.count()).isZero();                    // the claim was released
    }

    @Test
    void removingASuppression_allowsEmailingAgain_andNothingElseChanges() {

        suppress("a1@acme.test");
        assertThat(outcome(sendNew(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello"), a1)).isEqualTo("EXCLUDED");

        suppressionRepository.deleteAll();

        assertThat(outcome(sendNew(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello"), a1)).isEqualTo("ACCEPTED");
    }

    // ============================================================ full 100-record flow

    @Test
    void aHundredRecordBatch_inTenChunks_reconcilesExactly_withMixedOutcomes() {

        List<Lead> leads = new java.util.ArrayList<>();

        for (int i = 0; i < 100; i++) {
            leads.add(lead("Flow Co " + i, "Contact" + i, "flow" + i + "@flow.test", empA));
        }

        for (int i = 0; i <= 3; i++) {                     // no email
            leads.get(i).setEmail(null);
        }
        leads.get(4).setEmail("not an email");              // invalid
        leads.get(6).setEmail("FLOW5@flow.test");           // same address as #5, same chunk
        leads.get(15).setEmail(" flow5@FLOW.test ");        // same address as #5, a later chunk
        leadRepository.saveAll(leads);

        suppress("flow8@flow.test");                        // #8 is on the do-not-email list

        for (int i : new int[]{10, 11, 12}) {               // confirmed rejection first, accepted on retry
            when(brevo.trySend(eq("flow" + i + "@flow.test"), any(), any(), any()))
                    .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.FAILED, 400, "HTTP 400: rejected"))
                    .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));
        }

        for (int i : new int[]{20, 21}) {                   // ambiguous
            when(brevo.trySend(eq("flow" + i + "@flow.test"), any(), any(), any()))
                    .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.UNKNOWN, null, "read timeout"));
        }

        List<Long> ids = leads.stream().map(Lead::getId).toList();

        String batch = newBatch(BulkEmailTarget.LEAD, ids, "Hello {{contactPerson}}", "Dear {{contactPerson}}");

        int acceptedSeen = 0;

        for (int from = 0; from < 100; from += 10) {

            BulkEmailSendResponse r = bulk.send(chunk(batch, ids.subList(from, from + 10)));
            assertThat(r.getResults()).hasSize(10);
            acceptedSeen += r.getAccepted();
        }

        BulkEmailSendResponse view = bulk.results(batch);

        // 4 no email + 1 invalid + 2 duplicates + 1 suppressed = 8 excluded; 3 failed; 2 unknown
        assertThat(view.getResults()).hasSize(100);
        assertThat(view.getExcluded()).isEqualTo(8);
        assertThat(view.getFailed()).isEqualTo(3);
        assertThat(view.getUnknown()).isEqualTo(2);
        assertThat(view.getAccepted()).isEqualTo(87).isEqualTo(acceptedSeen);
        assertThat(view.getNotAttempted() + view.getInProgress()).isZero();
        assertThat(view.getAccepted() + view.getFailed() + view.getUnknown() + view.getExcluded()).isEqualTo(100);

        verify(brevo, times(92)).trySend(any(), any(), any(), any());      // 87 + 3 + 2

        // retry exactly the confirmed failures
        BulkEmailSendResponse retry = bulk.send(chunk(batch,
                List.of(ids.get(10), ids.get(11), ids.get(12))));
        assertThat(retry.getAccepted()).isEqualTo(3);

        BulkEmailSendResponse after = bulk.results(batch);
        assertThat(after.getAccepted()).isEqualTo(90);
        assertThat(after.getFailed()).isZero();
        assertThat(after.getUnknown()).isEqualTo(2);

        verify(brevo, times(95)).trySend(any(), any(), any(), any());
        verify(brevo, times(1)).trySend(eq("flow20@flow.test"), any(), any(), any());   // UNKNOWN never resent

        // a brand-new batch for the same hundred sends nothing at all
        BulkEmailPreviewResponse again = bulk.preview(request(BulkEmailTarget.LEAD, ids, "S", "B"));
        assertThat(again.getEligibleCount()).isZero();
    }

    private String code(BulkEmailSendResponse response, Lead lead) {
        return response.getResults().stream()
                .filter(r -> r.getId().equals(lead.getId())).findFirst().orElseThrow().getExclusionCode();
    }

    private void suppress(String email) {
        suppressionRepository.saveAndFlush(com.compact.crm.entity.EmailSuppression.builder()
                .emailNormalized(EmailAddresses.normalize(email))
                .reason(com.compact.crm.enums.SuppressionReason.OPT_OUT)
                .source("TEST").build());
    }

    // ============================================================ helpers

    private String outcome(BulkEmailSendResponse response, Lead lead) {
        return response.getResults().stream()
                .filter(r -> r.getId().equals(lead.getId())).findFirst().orElseThrow().getOutcome();
    }

    private BulkEmailPreviewResponse.Recipient by(BulkEmailPreviewResponse p, Lead lead) {
        return byId(p, lead.getId());
    }

    private BulkEmailPreviewResponse.Recipient byId(BulkEmailPreviewResponse p, Long id) {
        return p.getRecipients().stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
    }

    private String batch() {
        return "batch-" + java.util.UUID.randomUUID();
    }

    private BulkEmailRequest request(BulkEmailTarget target, List<Long> ids, String subject, String body) {
        BulkEmailRequest r = new BulkEmailRequest();
        r.setTarget(target);
        r.setIds(ids);
        r.setSubject(subject);
        r.setBody(body);
        return r;
    }

    private String newBatch(BulkEmailTarget target, List<Long> ids, String subject, String body) {
        return bulk.createBatch(request(target, ids, subject, body)).getBatchId();
    }

    private BulkEmailRequest chunk(String batchId, List<Long> ids) {
        BulkEmailRequest r = new BulkEmailRequest();
        r.setBatchId(batchId);
        r.setIds(ids);
        return r;
    }

    // Creates a batch for exactly these records and sends them as one chunk.
    private BulkEmailSendResponse sendNew(BulkEmailTarget target, List<Long> ids, String subject, String body) {
        return bulk.send(chunk(newBatch(target, ids, subject, body), ids));
    }

    private Employee employee(String name, String email, String phone, Role role, Employee boss) {
        return employeeRepository.save(Employee.builder()
                .name(name).email(email).phone(phone).password("x").role(role).manager(boss).build());
    }

    private Lead lead(String company, String contact, String email, Employee owner) {
        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson(contact).email(email).phone("9000000000")
                .leadStatus(LeadStatus.VALID).assignedEmployee(owner).build());
    }

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissions.computeIfAbsent(
                code, c -> permissionRepository.save(Permission.builder().code(c).build()));

        rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
    }

    private void authenticateAs(Employee actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }
}
