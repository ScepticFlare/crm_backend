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
import com.compact.crm.repository.EmailSendLogRepository;
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
        AccessControlService.class, CurrentUserService.class})
class BulkEmailServiceIntegrationTest {

    @MockitoBean private BrevoEmailClient brevo;

    @Autowired private BulkEmailService bulk;
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

        BulkEmailSendResponse sent = bulk.send(sendRequest(BulkEmailTarget.LEAD,
                List.of(b1.getId(), 999_999L), "Subject", "Body", batch()));

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

        BulkEmailSendResponse sent = bulk.send(sendRequest(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a4.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}", batch()));

        assertThat(sent.getAccepted()).isEqualTo(2);
        assertThat(sent.getExcluded()).isEqualTo(1);
        verify(brevo, times(2)).trySend(any(), any(), any(), any());
    }

    @Test
    void sameAddressInAnotherChunkOfTheSameBatch_isNotEmailedTwice() {

        String batch = batch();

        bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Dear {{contactPerson}}", batch));
        BulkEmailSendResponse second = bulk.send(sendRequest(BulkEmailTarget.LEAD,
                List.of(a4.getId()), "Hi", "Dear {{contactPerson}}", batch));

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

        assertThatThrownBy(() -> bulk.send(sendRequest(BulkEmailTarget.LEAD, tooMany, "S", "B", batch())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At most 10");

        assertThatThrownBy(() -> bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), "S", "B", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch id");

        assertThatThrownBy(() -> bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), " ", "B", batch())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), "S", "", batch())))
                .isInstanceOf(IllegalArgumentException.class);

        List<Long> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> bulk.preview(request(BulkEmailTarget.LEAD, withNull, "S", "B")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void aBatchWithNoEligibleRecipientsSendsNothing() {

        BulkEmailSendResponse sent = bulk.send(sendRequest(BulkEmailTarget.LEAD,
                List.of(a2.getId(), a3.getId()), "Hi", "Body", batch()));

        assertThat(sent.getAccepted()).isZero();
        assertThat(sent.getExcluded()).isEqualTo(2);
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    // ============================================================ personalisation / templates

    @Test
    void eachRecipientReceivesOnlyTheirOwnPersonalisedMessage_asAnIndividualEmail() {

        bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()),
                "Hello {{contactPerson}} at {{companyName}}", "Dear {{contactPerson}}, about {{companyName}}.", batch()));

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

        BulkEmailSendResponse sent = bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()),
                "Hi {{contactPerson}}", "Offer: [[ADMIN: describe the AMC offer here]] and {{nope}}", batch()));

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

        BulkEmailRequest request = sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()),
                "My edited subject", "My edited body for {{companyName}}", batch());
        request.setTemplateId(template.getId());

        bulk.send(request);

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

        BulkEmailRequest request = sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), "S", "B", batch());
        request.setTemplateId(template.getId());

        assertThatThrownBy(() -> bulk.send(request))
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

        BulkEmailSendResponse sent = bulk.send(sendRequest(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a6.getId(), a5.getId(), a2.getId()), "Hi", "Dear {{contactPerson}}", batch()));

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

        String batch = batch();
        BulkEmailRequest request = sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}", batch);

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

        String batch = batch();
        BulkEmailRequest request = sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "Dear {{contactPerson}}", batch);

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

        String batch = batch();

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

        BulkEmailSendResponse sent = bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello", batch));

        assertThat(outcome(sent, a1)).isEqualTo("UNKNOWN");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void aFreshPendingClaimIsInProgress_notResent() {

        String batch = batch();

        sendLogRepository.saveAndFlush(com.compact.crm.entity.EmailSendLog.builder()
                .batchId(batch).target(BulkEmailTarget.LEAD).targetId(a1.getId())
                .recipientEmail("a1@acme.test").status(com.compact.crm.enums.EmailSendStatus.PENDING)
                .sentByEmployeeId(empA.getId()).build());

        BulkEmailSendResponse sent = bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello", batch));

        assertThat(outcome(sent, a1)).isEqualTo("IN_PROGRESS");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void permissionsAreRevalidatedAtSendTime() {

        String batch = batch();

        // a record that was fine at review time is reassigned away before the send
        a1.setAssignedEmployee(empB);
        leadRepository.save(a1);

        BulkEmailSendResponse sent = bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello", batch));

        assertThat(outcome(sent, a1)).isEqualTo("EXCLUDED");
        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void aBatchBelongsToItsSender() {

        String batch = batch();
        bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId()), "Hi", "Hello", batch));

        authenticateAs(empB);

        assertThatThrownBy(() -> bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(b1.getId()), "Hi", "Hello", batch)))
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

        bulk.send(sendRequest(BulkEmailTarget.LEAD, List.of(a1.getId(), a6.getId()), "Hi", "SECRET-BODY-TEXT", batch()));

        var entries = activityLogRepository.findAll().stream()
                .filter(a -> a.getAction() == ActivityAction.EMAIL_SENT).toList();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntityId()).isEqualTo(a1.getId());
        assertThat(entries.get(0).getDescription()).doesNotContain("SECRET-BODY-TEXT");
        assertThat(sendLogRepository.findAll()).allMatch(l -> !String.valueOf(l.getDetail()).contains("SECRET-BODY-TEXT"));
    }

    @Test
    void opportunityBatchesAreLoggedAgainstTheOpportunity() {

        bulk.send(sendRequest(BulkEmailTarget.OPPORTUNITY, List.of(oppA1.getId()), "Hi", "Hello {{opportunityTitle}}", batch()));

        var entries = activityLogRepository.findAll().stream()
                .filter(a -> a.getAction() == ActivityAction.EMAIL_SENT).toList();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getModule()).isEqualTo(com.compact.crm.enums.ActivityModule.OPPORTUNITY);
        assertThat(entries.get(0).getEntityId()).isEqualTo(oppA1.getId());
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

    private BulkEmailRequest sendRequest(BulkEmailTarget target, List<Long> ids, String subject, String body, String batchId) {
        BulkEmailRequest r = request(target, ids, subject, body);
        r.setBatchId(batchId);
        return r;
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
