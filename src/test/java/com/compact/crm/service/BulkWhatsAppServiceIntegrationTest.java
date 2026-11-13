package com.compact.crm.service;

import com.compact.crm.dto.request.BulkWhatsAppRequest;
import com.compact.crm.dto.request.WhatsAppTemplateRequest;
import com.compact.crm.dto.response.BulkWhatsAppPreviewResponse;
import com.compact.crm.dto.response.BulkWhatsAppSendResponse;
import com.compact.crm.dto.response.WhatsAppAccessResponse;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.WhatsAppConsent;
import com.compact.crm.entity.WhatsAppSendLog;
import com.compact.crm.entity.WhatsAppTemplate;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.enums.WhatsAppConsentStatus;
import com.compact.crm.enums.WhatsAppDeliveryStatus;
import com.compact.crm.enums.WhatsAppSendStatus;
import com.compact.crm.exception.IntegrationNotConfiguredException;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.repository.WhatsAppConsentRepository;
import com.compact.crm.repository.WhatsAppSendLogRepository;
import com.compact.crm.repository.WhatsAppTemplateRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulk WhatsApp on a real H2 database with the real services and a FAKE provider
 * (WhatsAppClient is mocked) - no WhatsApp message is ever sent.
 */
@DataJpaTest
@Import({BulkWhatsAppService.class, WhatsAppTemplateService.class, WhatsAppConsentService.class,
        WhatsAppWebhookService.class, ActivityLogService.class, AccessControlService.class, CurrentUserService.class})
class BulkWhatsAppServiceIntegrationTest {

    @MockitoBean private WhatsAppClient client;

    @Autowired private BulkWhatsAppService bulk;
    @Autowired private WhatsAppTemplateService templates;
    @Autowired private WhatsAppConsentService consent;
    @Autowired private WhatsAppWebhookService webhooks;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private WhatsAppTemplateRepository templateRepository;
    @Autowired private WhatsAppConsentRepository consentRepository;
    @Autowired private WhatsAppSendLogRepository sendLogRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Employee admin;
    private Employee manager;
    private Employee empA;
    private Employee empB;

    private Lead a1;    // empA, 9876500001, opted in, contact Asha, product UPS
    private Lead a2;    // empA, no phone
    private Lead a3;    // empA, landline
    private Lead a4;    // empA, 9876500004, no consent on record
    private Lead a5;    // empA, 9876500005, opted out
    private Lead a6;    // empA, SAME number as a1 (written differently)
    private Lead a7;    // empA, 9876500007, opted in, blank contact person
    private Lead a8;    // empA, 9876500008, opted in, contact Zed
    private Lead b1;    // empB, 9876500011, opted in

    private Opportunity oppA1;

    private WhatsAppTemplate template;    // uses contactPerson + companyName
    private final Map<String, Permission> permissions = new HashMap<>();
    private final AtomicInteger messageIds = new AtomicInteger();

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        for (String code : List.of(AccessControlService.WHATSAPP_SEND, AccessControlService.OPPORTUNITY_VIEW,
                AccessControlService.LEAD_VIEW, AccessControlService.LEAD_MANAGE)) {
            grant(adminRole, code, Scope.ALL);
            grant(managerRole, code, Scope.TEAM);
            grant(employeeRole, code, Scope.OWN);
        }

        grant(adminRole, AccessControlService.WHATSAPP_TEMPLATE_MANAGE, Scope.ALL);
        grant(adminRole, AccessControlService.WHATSAPP_CONSENT_MANAGE, Scope.ALL);

        admin = employee("Admin", "adm@t.test", "9600000001", adminRole, null);
        manager = employee("Manager", "mgr@t.test", "9600000002", managerRole, null);
        empA = employee("Emp A", "ea@t.test", "9600000003", employeeRole, manager);
        empB = employee("Emp B", "eb@t.test", "9600000004", employeeRole, null);

        Product ups = productRepository.save(Product.builder().name("Enterprise Series UPS").isActive(true).build());

        a1 = lead("Acme Ltd", "Asha", "9876500001", empA);
        a1.getLeadProducts().add(LeadProduct.builder().lead(a1).product(ups).quantity(1).build());
        a1 = leadRepository.save(a1);
        a2 = lead("No Phone Co", "Nina", null, empA);
        a3 = lead("Landline Co", "Lal", "04314021154", empA);
        a4 = lead("No Consent Co", "Neel", "9876500004", empA);
        a5 = lead("Opted Out Co", "Omar", "9876500005", empA);
        a6 = lead("Acme Branch", "Asha B", "+91 98765 00001", empA);
        a7 = lead("Blank Contact Co", "  ", "9876500007", empA);
        a8 = lead("Zed Corp", "Zed", "9876500008", empA);
        b1 = lead("Other Owner Co", "Bela", "9876500011", empB);

        oppA1 = opportunityRepository.save(Opportunity.builder().title("UPS upgrade 2027").lead(a1)
                .contactPerson("Ravi").contactPhone("9876500021").build());

        for (String phone : List.of("919876500001", "919876500007", "919876500008", "919876500011", "919876500021")) {
            optIn(phone);
        }
        optOut("919876500005");

        template = templateRepository.save(WhatsAppTemplate.builder()
                .name("Follow-up").body("Hi {{contactPerson}}, greetings from Compact Systems to {{companyName}}.")
                .version(1).isActive(true).build());

        when(client.isConfigured()).thenReturn(true);
        when(client.sessionStatus()).thenReturn("WORKING");
        when(client.isReady()).thenReturn(true);
        when(client.sendText(anyString(), anyString())).thenAnswer(inv -> new WhatsAppClient.SendOutcome(
                WhatsAppClient.Result.ACCEPTED, "msg-" + messageIds.incrementAndGet(), null, null, false));

        authenticateAs(empA);
    }

    // ============================================================ authorization

    @Test
    void employee_cannotSeeOrMessageARecordOutsideTheirScope_andIdsCannotBypassIt() {

        BulkWhatsAppPreviewResponse preview = bulk.preview(request(BulkEmailTarget.LEAD,
                List.of(a1.getId(), b1.getId(), 999_999L), template.getId()));

        assertThat(by(preview, a1).isEligible()).isTrue();

        for (Long id : List.of(b1.getId(), 999_999L)) {
            BulkWhatsAppPreviewResponse.Recipient r = byId(preview, id);
            assertThat(r.isEligible()).isFalse();
            assertThat(r.getExclusionCode()).isEqualTo("NOT_AVAILABLE");
            assertThat(r.getCompanyName()).isNull();
            assertThat(r.getPhoneMasked()).isNull();
            assertThat(r.getRenderedText()).isNull();
        }

        BulkWhatsAppSendResponse sent = sendNew(BulkEmailTarget.LEAD, List.of(b1.getId(), 999_999L));

        assertThat(sent.getExcluded()).isEqualTo(2);
        verify(client, never()).sendText(anyString(), anyString());
        assertThat(sendLogRepository.count()).isZero();
    }

    @Test
    void manager_reachesTheirTeam_adminReachesEveryone() {

        authenticateAs(manager);
        BulkWhatsAppPreviewResponse m = bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId(), b1.getId()), null));
        assertThat(by(m, a1).isEligible()).isTrue();
        assertThat(by(m, b1).getExclusionCode()).isEqualTo("NOT_AVAILABLE");

        authenticateAs(admin);
        assertThat(bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId(), b1.getId()), null)).getEligibleCount())
                .isEqualTo(2);
    }

    @Test
    void withoutWhatsAppSend_everythingIsRefused() {

        Role none = roleRepository.save(Role.builder().name("NONE").rank(1).build());
        Employee nobody = employee("Nobody", "no@t.test", "9600000009", none, null);
        authenticateAs(nobody);

        assertThat(bulk.access().canSend()).isFalse();
        assertThatThrownBy(() -> bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId()), null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bulk.createBatch(request(BulkEmailTarget.LEAD, List.of(a1.getId()), template.getId())))
                .isInstanceOf(AccessDeniedException.class);
        verify(client, never()).sendText(anyString(), anyString());
    }

    // ============================================================ eligibility

    @Test
    void preview_explainsEveryExclusion_andRendersEligibleRecipientsFromTheDatabase() {

        BulkWhatsAppPreviewResponse p = bulk.preview(request(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a2.getId(), a3.getId(), a4.getId(), a5.getId(), a6.getId(), a7.getId(), a8.getId()),
                template.getId()));

        assertThat(by(p, a1).isEligible()).isTrue();
        assertThat(by(p, a1).getRenderedText()).isEqualTo("Hi Asha, greetings from Compact Systems to Acme Ltd.");
        assertThat(by(p, a1).getPhoneMasked()).isEqualTo("+91 ******0001");

        assertThat(by(p, a2).getExclusionCode()).isEqualTo("NO_PHONE");
        assertThat(by(p, a3).getExclusionCode()).isEqualTo("INVALID_PHONE");
        assertThat(by(p, a4).getExclusionCode()).isEqualTo("NO_OPT_IN");
        assertThat(by(p, a5).getExclusionCode()).isEqualTo("OPTED_OUT");
        assertThat(by(p, a6).getExclusionCode()).isEqualTo("DUPLICATE_PHONE");
        assertThat(by(p, a6).getExclusionMessage()).contains("Acme Ltd");
        assertThat(by(p, a1).getWarnings()).anyMatch(w -> w.contains("share this number"));
        assertThat(by(p, a7).getExclusionCode()).isEqualTo("UNRESOLVED_VARIABLE");
        assertThat(by(p, a7).getExclusionMessage()).contains("Contact person is missing");
        assertThat(by(p, a8).getRenderedText()).isEqualTo("Hi Zed, greetings from Compact Systems to Zed Corp.");

        assertThat(p.getEligibleCount()).isEqualTo(2);
        assertThat(p.getExcludedCount()).isEqualTo(6);
        assertThat(p.isProviderReady()).isTrue();

        // no full number anywhere in the response
        for (BulkWhatsAppPreviewResponse.Recipient r : p.getRecipients()) {
            if (r.getPhoneMasked() != null) {
                assertThat(r.getPhoneMasked()).doesNotContain("98765");
            }
        }
    }

    @Test
    void preview_withoutATemplate_checksRecipientsOnly() {

        BulkWhatsAppPreviewResponse p = bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId(), a7.getId()), null));

        assertThat(by(p, a1).getRenderedText()).isNull();
        assertThat(by(p, a7).isEligible()).isTrue(); // nothing to resolve yet
    }

    @Test
    void anInactiveTemplateCannotBeUsed() {

        template.setIsActive(false);
        templateRepository.save(template);

        assertThatThrownBy(() -> bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId()), template.getId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no longer active");
        assertThatThrownBy(() -> bulk.createBatch(request(BulkEmailTarget.LEAD, List.of(a1.getId()), template.getId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opportunities_useTheirOwnContactNumber_andTitle_neverTheLeadsNumber() {

        WhatsAppTemplate t = templateRepository.save(WhatsAppTemplate.builder()
                .name("Opp").body("Hi {{contactPerson}}, about {{opportunityTitle}}").version(1).isActive(true).build());

        BulkWhatsAppPreviewResponse p = bulk.preview(request(BulkEmailTarget.OPPORTUNITY, List.of(oppA1.getId()), t.getId()));
        BulkWhatsAppPreviewResponse.Recipient r = byId(p, oppA1.getId());

        assertThat(r.isEligible()).isTrue();
        assertThat(r.getPhoneMasked()).endsWith("0021");
        assertThat(r.getRenderedText()).isEqualTo("Hi Ravi, about UPS upgrade 2027");

        oppA1.setContactPhone(null);
        opportunityRepository.save(oppA1);

        assertThat(byId(bulk.preview(request(BulkEmailTarget.OPPORTUNITY, List.of(oppA1.getId()), t.getId())),
                oppA1.getId()).getExclusionCode()).isEqualTo("NO_PHONE");
    }

    // ============================================================ consent

    @Test
    void anOptOutThroughOneRecord_blocksEveryRecordSharingTheNumber() {

        // a6 shares a1's number; an opt-out recorded via a6's lead page blocks a1 too
        authenticateAs(empA);
        consent.recordForLead(a6.getId(), consentRequest(WhatsAppConsentStatus.OPTED_OUT));

        BulkWhatsAppPreviewResponse p = bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId()), template.getId()));

        assertThat(by(p, a1).getExclusionCode()).isEqualTo("OPTED_OUT");
    }

    @Test
    void consentIsCheckedAgainAtSendTime_anOptOutAfterPreviewWins() {

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a8.getId()));

        // the customer replied STOP between batch creation and sending
        consent.optOutFromInbound("919876500001", "test");

        BulkWhatsAppSendResponse sent = bulk.send(chunk(batchId, List.of(a1.getId(), a8.getId())));

        assertThat(outcome(sent, a1)).isEqualTo("EXCLUDED");
        assertThat(code(sent, a1)).isEqualTo("OPTED_OUT");
        assertThat(outcome(sent, a8)).isEqualTo("ACCEPTED");

        verify(client, never()).sendText(eq("919876500001"), anyString());
        verify(client, times(1)).sendText(eq("919876500008"), anyString());
        // the released claim leaves no row behind
        assertThat(sendLogRepository.findByBatchIdAndTargetAndTargetId(batchId, BulkEmailTarget.LEAD, a1.getId())).isEmpty();
    }

    // ============================================================ batches

    @Test
    void theBatchPinsTheTemplateVersionAndText_aLaterEditChangesNothing() {

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()));

        authenticateAs(admin);
        templates.update(template.getId(), new WhatsAppTemplateRequest("Follow-up", null,
                "Completely different text for {{companyName}}"));
        assertThat(templateRepository.findById(template.getId()).orElseThrow().getVersion()).isEqualTo(2);

        authenticateAs(empA);
        bulk.send(chunk(batchId, List.of(a1.getId())));

        verify(client).sendText("919876500001", "Hi Asha, greetings from Compact Systems to Acme Ltd.");

        WhatsAppSendLog row = sendLogRepository.findByBatchIdOrderByIdAsc(batchId).get(0);
        assertThat(row.getTemplateVersion()).isEqualTo(1);
        assertThat(bulk.results(batchId).getBatch().getTemplateVersion()).isEqualTo(1);
    }

    @Test
    void aChunkMustBeASubsetOfTheBatch_andOnlyTheOwnerMaySend() {

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()));

        assertThatThrownBy(() -> bulk.send(chunk(batchId, List.of(a1.getId(), a8.getId()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not part of this batch");

        BulkWhatsAppRequest otherTemplate = chunk(batchId, List.of(a1.getId()));
        otherTemplate.setTemplateId(template.getId() + 99);
        assertThatThrownBy(() -> bulk.send(otherTemplate)).isInstanceOf(IllegalArgumentException.class);

        authenticateAs(empB);
        assertThatThrownBy(() -> bulk.send(chunk(batchId, List.of(a1.getId()))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bulk.results(batchId)).isInstanceOf(AccessDeniedException.class);

        // an admin may read (not send) another user's batch
        authenticateAs(admin);
        assertThat(bulk.results(batchId).getResults()).hasSize(1);

        verify(client, never()).sendText(anyString(), anyString());
    }

    @Test
    void chunksAreCappedAtTheConfiguredSize() {

        String batchId = newBatch(BulkEmailTarget.LEAD,
                List.of(a1.getId(), a2.getId(), a3.getId(), a4.getId(), a5.getId(), a8.getId()));

        assertThatThrownBy(() -> bulk.send(chunk(batchId,
                List.of(a1.getId(), a2.getId(), a3.getId(), a4.getId(), a5.getId(), a8.getId()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At most 5");
    }

    // ============================================================ provider state

    @Test
    void aDisconnectedSession_refusesNewBatchesAndSends_beforeAnyClaim() {

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()));

        doThrow(new IntegrationNotConfiguredException("WhatsApp is not connected (session status: SCAN_QR_CODE)."))
                .when(client).requireReady();

        assertThatThrownBy(() -> bulk.createBatch(request(BulkEmailTarget.LEAD, List.of(a8.getId()), template.getId())))
                .isInstanceOf(IntegrationNotConfiguredException.class);

        assertThatThrownBy(() -> bulk.send(chunk(batchId, List.of(a1.getId()))))
                .isInstanceOf(IntegrationNotConfiguredException.class);

        verify(client, never()).sendText(anyString(), anyString());
        assertThat(sendLogRepository.count()).isZero();
    }

    @Test
    void access_reportsProviderReadiness_onlyToThoseAllowedToSend() {

        WhatsAppAccessResponse ok = bulk.access();
        assertThat(ok.canSend()).isTrue();
        assertThat(ok.scope()).isEqualTo(Scope.OWN);
        assertThat(ok.ready()).isTrue();
        assertThat(ok.chunkSize()).isEqualTo(5);

        when(client.sessionStatus()).thenReturn("SCAN_QR_CODE");
        WhatsAppAccessResponse disconnected = bulk.access();
        assertThat(disconnected.ready()).isFalse();
        assertThat(disconnected.message()).contains("SCAN_QR_CODE").doesNotContain("key");

        when(client.isConfigured()).thenReturn(false);
        WhatsAppAccessResponse off = bulk.access();
        assertThat(off.configured()).isFalse();
        assertThat(off.sessionStatus()).isNull();
        assertThat(off.message()).contains("not configured");
    }

    // ============================================================ retries / recovery

    @Test
    void aConfirmedFailureMayBeRetried_andIsSentAgainExactlyOnce() {

        when(client.sendText(eq("919876500001"), anyString()))
                .thenReturn(new WhatsAppClient.SendOutcome(WhatsAppClient.Result.FAILED, null, 400, "bad", false))
                .thenReturn(new WhatsAppClient.SendOutcome(WhatsAppClient.Result.ACCEPTED, "msg-retry", null, null, false));

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()));

        assertThat(outcome(bulk.send(chunk(batchId, List.of(a1.getId()))), a1)).isEqualTo("FAILED");
        assertThat(outcome(bulk.send(chunk(batchId, List.of(a1.getId()))), a1)).isEqualTo("ACCEPTED");

        // a third request only replays the recorded outcome
        BulkWhatsAppSendResponse again = bulk.send(chunk(batchId, List.of(a1.getId())));
        assertThat(outcome(again, a1)).isEqualTo("ACCEPTED");
        assertThat(again.getResults().get(0).isAlreadyProcessed()).isTrue();

        verify(client, times(2)).sendText(eq("919876500001"), anyString());
        assertThat(sendLogRepository.findByBatchIdOrderByIdAsc(batchId)).hasSize(1);
    }

    @Test
    void anUnknownOutcomeIsNeverRetried() {

        when(client.sendText(eq("919876500001"), anyString()))
                .thenReturn(new WhatsAppClient.SendOutcome(WhatsAppClient.Result.UNKNOWN, null, null, "timeout", true));

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()));

        assertThat(outcome(bulk.send(chunk(batchId, List.of(a1.getId()))), a1)).isEqualTo("UNKNOWN");
        assertThat(outcome(bulk.send(chunk(batchId, List.of(a1.getId()))), a1)).isEqualTo("UNKNOWN");
        assertThat(outcome(bulk.results(batchId), a1)).isEqualTo("UNKNOWN");

        verify(client, times(1)).sendText(anyString(), anyString());

        // and a NEW batch to the same number is blocked inside the de-duplication window
        BulkWhatsAppPreviewResponse p = bulk.preview(request(BulkEmailTarget.LEAD, List.of(a1.getId()), template.getId()));
        assertThat(by(p, a1).getExclusionCode()).isEqualTo("RECENTLY_MESSAGED");
    }

    @Test
    void aProviderLevelFailurePausesTheChunk_theRestStayUnclaimedAndCanBeSentLater() {

        when(client.sendText(eq("919876500001"), anyString()))
                .thenReturn(new WhatsAppClient.SendOutcome(WhatsAppClient.Result.UNKNOWN, null, 503, "down", true));

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a8.getId()));

        BulkWhatsAppSendResponse sent = bulk.send(chunk(batchId, List.of(a1.getId(), a8.getId())));

        assertThat(sent.isPaused()).isTrue();
        assertThat(outcome(sent, a1)).isEqualTo("UNKNOWN");
        assertThat(outcome(sent, a8)).isEqualTo("NOT_ATTEMPTED");
        assertThat(code(sent, a8)).isEqualTo("PROVIDER_UNAVAILABLE");
        verify(client, never()).sendText(eq("919876500008"), anyString());
        assertThat(sendLogRepository.findByBatchIdAndTargetAndTargetId(batchId, BulkEmailTarget.LEAD, a8.getId())).isEmpty();

        // provider back: the paused recipient is sent; the UNKNOWN one is not touched again
        BulkWhatsAppSendResponse resumed = bulk.send(chunk(batchId, List.of(a1.getId(), a8.getId())));
        assertThat(outcome(resumed, a8)).isEqualTo("ACCEPTED");
        assertThat(outcome(resumed, a1)).isEqualTo("UNKNOWN");
        verify(client, times(1)).sendText(eq("919876500001"), anyString());
    }

    @Test
    void aStaleInFlightClaimReadsAsUnknown_andIsNotResent() {

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()));

        sendLogRepository.saveAndFlush(WhatsAppSendLog.builder().batchId(batchId).target(BulkEmailTarget.LEAD)
                .targetId(a1.getId()).phoneE164("919876500001").status(WhatsAppSendStatus.PENDING)
                .updatedAt(LocalDateTime.now().minusMinutes(10)).build());

        assertThat(outcome(bulk.send(chunk(batchId, List.of(a1.getId()))), a1)).isEqualTo("UNKNOWN");
        verify(client, never()).sendText(anyString(), anyString());
    }

    @Test
    void theResultsViewAccountsForEveryRecord_afterARefresh() {

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a4.getId(), a8.getId()));

        bulk.send(chunk(batchId, List.of(a1.getId())));

        BulkWhatsAppSendResponse view = bulk.results(batchId);

        assertThat(view.getResults()).extracting(BulkWhatsAppSendResponse.Result::getId)
                .containsExactly(a1.getId(), a4.getId(), a8.getId());
        assertThat(outcome(view, a1)).isEqualTo("ACCEPTED");
        assertThat(code(view, a4)).isEqualTo("NO_OPT_IN");
        assertThat(outcome(view, a8)).isEqualTo("NOT_ATTEMPTED");
        assertThat(view.getBatch().getBody()).contains("{{contactPerson}}");
    }

    // ============================================================ audit

    @Test
    void onlyProviderAcceptanceIsLoggedAsASend_andNeverAsDelivery() {

        when(client.sendText(eq("919876500008"), anyString()))
                .thenReturn(new WhatsAppClient.SendOutcome(WhatsAppClient.Result.FAILED, null, 400, "bad", false));

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId(), a8.getId()));
        bulk.send(chunk(batchId, List.of(a1.getId(), a8.getId())));

        List<ActivityLog> sends = activityLogRepository.findAll().stream()
                .filter(l -> l.getAction() == ActivityAction.WHATSAPP_SENT).toList();

        assertThat(sends).hasSize(1);
        assertThat(sends.get(0).getEntityId()).isEqualTo(a1.getId());
        assertThat(sends.get(0).getDescription()).contains("not proof of delivery").doesNotContain("9876500001");
    }

    // ============================================================ delivery status

    @Test
    void aDeliveryWebhookThatArrivesBeforeTheSendIsRecorded_isAppliedOnceTheRowExists() {

        when(client.sendText(eq("919876500001"), anyString()))
                .thenReturn(new WhatsAppClient.SendOutcome(WhatsAppClient.Result.ACCEPTED, "early-1", null, null, false));

        // the provider's DEVICE ack races ahead of the send response
        assertThat(webhooks.handle(ack("early-1", 2))).isEqualTo(WhatsAppWebhookService.Outcome.PENDING);

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()));
        BulkWhatsAppSendResponse sent = bulk.send(chunk(batchId, List.of(a1.getId())));

        assertThat(sent.getResults().get(0).getDeliveryStatus()).isEqualTo("DELIVERED");
        assertThat(sendLogRepository.findByProviderMessageId("early-1").orElseThrow().getDeliveryStatus())
                .isEqualTo(WhatsAppDeliveryStatus.DELIVERED);
    }

    @Test
    void deliveryStatusesOnlyMoveForward_andShowInTheResults() {

        when(client.sendText(eq("919876500001"), anyString()))
                .thenReturn(new WhatsAppClient.SendOutcome(WhatsAppClient.Result.ACCEPTED, "m-1", null, null, false));

        String batchId = newBatch(BulkEmailTarget.LEAD, List.of(a1.getId()));
        bulk.send(chunk(batchId, List.of(a1.getId())));

        webhooks.handle(ack("m-1", 3));   // READ
        webhooks.handle(ack("m-1", 2));   // late DEVICE
        webhooks.handle(ack("m-1", 1));   // late SERVER
        webhooks.handle(ack("m-1", -1));  // late ERROR

        assertThat(bulk.results(batchId).getResults().get(0).getDeliveryStatus()).isEqualTo("READ");
    }

    // ============================================================ helpers

    private byte[] ack(String messageId, int ack) {
        return ("{\"event\":\"message.ack\",\"session\":\"default\",\"payload\":{\"id\":\"" + messageId
                + "\",\"fromMe\":true,\"ack\":" + ack + "}}").getBytes(StandardCharsets.UTF_8);
    }

    private com.compact.crm.dto.request.WhatsAppConsentRequest consentRequest(WhatsAppConsentStatus status) {
        com.compact.crm.dto.request.WhatsAppConsentRequest r = new com.compact.crm.dto.request.WhatsAppConsentRequest();
        r.setStatus(status);
        return r;
    }

    private void optIn(String phone) {
        consentRepository.save(WhatsAppConsent.builder().phoneE164(phone).status(WhatsAppConsentStatus.OPTED_IN)
                .source("ENQUIRY_FORM").evidence("test").consentedAt(LocalDateTime.now().minusDays(5)).build());
    }

    private void optOut(String phone) {
        consentRepository.save(WhatsAppConsent.builder().phoneE164(phone).status(WhatsAppConsentStatus.OPTED_OUT)
                .source("INBOUND_STOP").consentedAt(LocalDateTime.now().minusDays(1)).build());
    }

    private String outcome(BulkWhatsAppSendResponse response, Lead lead) {
        return response.getResults().stream().filter(r -> r.getId().equals(lead.getId())).findFirst()
                .orElseThrow().getOutcome();
    }

    private String code(BulkWhatsAppSendResponse response, Lead lead) {
        return response.getResults().stream().filter(r -> r.getId().equals(lead.getId())).findFirst()
                .orElseThrow().getExclusionCode();
    }

    private BulkWhatsAppPreviewResponse.Recipient by(BulkWhatsAppPreviewResponse p, Lead lead) {
        return byId(p, lead.getId());
    }

    private BulkWhatsAppPreviewResponse.Recipient byId(BulkWhatsAppPreviewResponse p, Long id) {
        return p.getRecipients().stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
    }

    private BulkWhatsAppRequest request(BulkEmailTarget target, List<Long> ids, Long templateId) {
        BulkWhatsAppRequest r = new BulkWhatsAppRequest();
        r.setTarget(target);
        r.setIds(ids);
        r.setTemplateId(templateId);
        return r;
    }

    private String newBatch(BulkEmailTarget target, List<Long> ids) {
        return bulk.createBatch(request(target, ids, template.getId())).getBatchId();
    }

    private BulkWhatsAppRequest chunk(String batchId, List<Long> ids) {
        BulkWhatsAppRequest r = new BulkWhatsAppRequest();
        r.setBatchId(batchId);
        r.setIds(ids);
        return r;
    }

    private BulkWhatsAppSendResponse sendNew(BulkEmailTarget target, List<Long> ids) {
        return bulk.send(chunk(newBatch(target, ids), ids));
    }

    private Employee employee(String name, String email, String phone, Role role, Employee boss) {
        return employeeRepository.save(Employee.builder()
                .name(name).email(email).phone(phone).password("x").role(role).manager(boss).build());
    }

    private Lead lead(String company, String contact, String phone, Employee owner) {
        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson(contact).phone(phone)
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
