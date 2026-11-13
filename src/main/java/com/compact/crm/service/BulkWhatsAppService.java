package com.compact.crm.service;

import com.compact.crm.dto.request.BulkWhatsAppRequest;
import com.compact.crm.dto.response.BulkWhatsAppBatchResponse;
import com.compact.crm.dto.response.BulkWhatsAppPreviewResponse;
import com.compact.crm.dto.response.BulkWhatsAppSendResponse;
import com.compact.crm.dto.response.WhatsAppAccessResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.WhatsAppBulkBatch;
import com.compact.crm.entity.WhatsAppSendLog;
import com.compact.crm.entity.WhatsAppTemplate;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.enums.WhatsAppConsentStatus;
import com.compact.crm.enums.WhatsAppSendStatus;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.WhatsAppBulkBatchRepository;
import com.compact.crm.repository.WhatsAppSendLogRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static com.compact.crm.security.AccessControlService.WHATSAPP_SEND;

// Bulk WhatsApp for Leads and Opportunities: one INDIVIDUAL text message per
// recipient through WhatsAppClient (WAHA), rendered from an admin-made CRM
// template, to opted-in numbers only, with the WHATSAPP_SEND scope (ADMIN ALL /
// MANAGER TEAM / EMPLOYEE OWN). Same sending model as BulkEmailService:
//
//  - A BATCH is created first (POST /batches). The SERVER issues its id and pins
//    the owner, target, template id + version + body snapshot (+ hash) and the
//    ordered recipient record ids (at most `max-recipients`). Editing the
//    template later never changes an existing batch.
//  - The browser sends the batch in CHUNKS of at most `max-chunk` records; a
//    chunk must be a subset of the pinned list and always sends the pinned text.
//    Another user cannot send or read the batch (an admin may read it).
//  - Every chunk re-authorizes and re-validates each record against CURRENT data
//    (scope, number, consent, render) - the preview is advisory only.
//  - A recipient whose outcome is already recorded is answered from that record
//    FIRST, before the live record is looked at again.
//  - Each recipient is CLAIMED in whatsapp_send_log (unique per batch + record)
//    before the provider is called, so a double click, a repeated request or a
//    retried chunk can never message the same recipient twice. Consent and
//    earlier claims on the same number are checked again right after the claim,
//    immediately before the provider call.
//  - A number is not messaged again by a NEW batch while an ACCEPTED, UNKNOWN or
//    in-flight attempt to it is recorded inside the de-duplication window
//    (`crm.whatsapp.bulk.dedupe-hours`, default 24). Only a confirmed FAILED can
//    be retried. There is no "send again anyway" for UNKNOWN - WAHA offers no
//    idempotency key, and UNKNOWN can mean "delivered".
//  - Sending is refused (503) unless the provider is configured AND its session
//    is WORKING - checked before any claim. Within a chunk, sends are spaced by a
//    fixed `min-interval-ms`; a provider-level failure (timeout, 5xx, session /
//    auth / throttling error) PAUSES the chunk: the rest are left NOT_ATTEMPTED
//    (unclaimed, safe to send later) instead of being pushed at a failing provider.
//  - ACCEPTED is the provider taking the message, NOT delivery. Delivery / read /
//    failure come later from webhooks (WhatsAppWebhookService).
//
// Not @Transactional on purpose: provider calls must never run inside an open
// DB transaction, and each recipient's outcome is independent.
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkWhatsAppService {

    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    // A PENDING claim older than this means the process died (or the final DB
    // write failed) after the provider may have been called: outcome UNKNOWN.
    private static final Duration PENDING_STALE_AFTER = Duration.ofMinutes(2);

    // Attempts that may have reached (or still be reaching) the person.
    private static final List<WhatsAppSendStatus> ACTIVE =
            List.of(WhatsAppSendStatus.ACCEPTED, WhatsAppSendStatus.UNKNOWN, WhatsAppSendStatus.PENDING);

    private static final String PAUSE_MESSAGE =
            "Sending paused: the WhatsApp provider stopped responding normally. Nothing was sent to this recipient.";

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final WhatsAppSendLogRepository sendLogRepository;
    private final WhatsAppBulkBatchRepository batchRepository;
    private final WhatsAppTemplateService templateService;
    private final WhatsAppConsentService consentService;
    private final WhatsAppWebhookService webhookService;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;
    private final ActivityLogService activityLogService;
    private final WhatsAppClient whatsAppClient;

    @Value("${crm.whatsapp.bulk.max-recipients:100}")
    private int maxRecipients = 100;

    @Value("${crm.whatsapp.bulk.max-chunk:5}")
    private int maxChunk = 5;

    @Value("${crm.whatsapp.bulk.dedupe-hours:24}")
    private int dedupeHours = 24;

    // Fixed spacing between two provider calls within one request.
    @Value("${crm.whatsapp.bulk.min-interval-ms:1500}")
    private long minIntervalMs = 1500;

    // ------------------------------------------------------------------- access

    public WhatsAppAccessResponse access() {

        Employee employee = currentUserService.getCurrentEmployee();
        Scope scope = accessControlService.resolveScope(employee, WHATSAPP_SEND);

        if (scope == null) {
            return new WhatsAppAccessResponse(false, null, false, null, false, null, maxRecipients, maxChunk);
        }

        boolean configured = whatsAppClient.isConfigured();
        String status = configured ? whatsAppClient.sessionStatus() : null;
        boolean ready = configured && "WORKING".equals(status);

        return new WhatsAppAccessResponse(true, scope, configured, status, ready,
                providerMessage(configured, status), maxRecipients, maxChunk);
    }

    // ------------------------------------------------------------------ preview

    public BulkWhatsAppPreviewResponse preview(BulkWhatsAppRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, WHATSAPP_SEND);

        requireTarget(request.getTarget());

        List<Long> ids = distinctIds(request.getIds(), maxRecipients);

        String body = null;
        Integer version = null;

        if (request.getTemplateId() != null) {
            WhatsAppTemplate template = templateService.requireUsable(request.getTemplateId());
            body = template.getBody();
            version = template.getVersion();
        }

        List<Eval> evals = evaluate(employee, request.getTarget(), ids, body, "");

        List<BulkWhatsAppPreviewResponse.Recipient> recipients = evals.stream().map(e -> e.dto).toList();
        long eligible = recipients.stream().filter(BulkWhatsAppPreviewResponse.Recipient::isEligible).count();

        boolean configured = whatsAppClient.isConfigured();
        String status = configured ? whatsAppClient.sessionStatus() : null;
        boolean ready = configured && "WORKING".equals(status);

        return new BulkWhatsAppPreviewResponse(request.getTarget(), ids.size(), (int) eligible,
                (int) (recipients.size() - eligible), request.getTemplateId(), version,
                ready, providerMessage(configured, status), recipients);
    }

    // ------------------------------------------------------------ create batch

    public BulkWhatsAppBatchResponse createBatch(BulkWhatsAppRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, WHATSAPP_SEND);

        requireTarget(request.getTarget());

        if (request.getTemplateId() == null) {
            throw new IllegalArgumentException("Choose a WhatsApp template.");
        }

        List<Long> ids = distinctIds(request.getIds(), maxRecipients);

        WhatsAppTemplate template = templateService.requireUsable(request.getTemplateId());

        // No batch is started that could not be sent right now.
        whatsAppClient.requireReady();

        WhatsAppBulkBatch saved = batchRepository.save(WhatsAppBulkBatch.builder()
                .batchId(UUID.randomUUID().toString())
                .ownerEmployeeId(employee.getId())
                .target(request.getTarget())
                .templateId(template.getId())
                .templateVersion(template.getVersion())
                .templateName(template.getName())
                .body(template.getBody())
                .contentHash(hash(template.getBody()))
                .recipientIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .recipientCount(ids.size())
                .build());

        return info(saved, false);
    }

    // --------------------------------------------------------------------- send

    // Sends one chunk of an existing batch. Safe to repeat: recipients already
    // handled are answered from the log and never sent again.
    public BulkWhatsAppSendResponse send(BulkWhatsAppRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, WHATSAPP_SEND);

        WhatsAppBulkBatch batch = loadBatch(request.getBatchId(), employee, false);

        verifyUnchanged(batch, request);

        List<Long> ids = distinctIds(request.getIds(), maxChunk);

        if (!new HashSet<>(batch.recipientIdList()).containsAll(ids)) {
            throw new IllegalArgumentException("The request contains records that are not part of this batch.");
        }

        if (!hash(batch.getBody()).equals(batch.getContentHash())) {
            throw new IllegalStateException("This batch's stored message failed its integrity check. Create a new batch.");
        }

        Map<Long, BulkWhatsAppSendResponse.Result> done = new HashMap<>();
        List<Long> pending = new ArrayList<>();

        for (Long id : ids) {

            Optional<WhatsAppSendLog> existing = sendLogRepository.findByBatchIdAndTargetAndTargetId(
                    batch.getBatchId(), batch.getTarget(), id);

            // Recorded outcomes are answered BEFORE the live record is looked at.
            BulkWhatsAppSendResponse.Result replay = existing.map(this::replay).orElse(null);

            if (replay != null) {
                done.put(id, replay);
            } else {
                pending.add(id);
            }
        }

        boolean paused = false;
        String pauseReason = null;

        if (!pending.isEmpty()) {

            // Before any recipient is claimed: no claims (or FAILED / UNKNOWN rows)
            // are left behind by a provider that cannot send right now.
            whatsAppClient.requireReady();

            // Revalidated NOW, not trusted from the earlier preview.
            List<Eval> evals = evaluate(employee, batch.getTarget(), pending, batch.getBody(), batch.getBatchId());

            boolean first = true;

            for (Eval ev : evals) {

                if (!ev.dto.isEligible()) {
                    done.put(ev.id, excluded(ev, ev.dto.getExclusionCode(), ev.dto.getExclusionMessage()));
                    continue;
                }

                if (paused) {
                    done.put(ev.id, new BulkWhatsAppSendResponse.Result(ev.id, ev.dto.getCompanyName(),
                            ev.dto.getPhoneMasked(), "NOT_ATTEMPTED", PAUSE_MESSAGE, false,
                            "PROVIDER_UNAVAILABLE", null, null));
                    continue;
                }

                if (duplicateInBatch(batch, ev)) {
                    done.put(ev.id, excluded(ev, "DUPLICATE_PHONE",
                            "Another record in this batch already has this WhatsApp number - one message per number."));
                    continue;
                }

                if (!first) {
                    pace();
                }

                Delivery delivery = deliver(employee, batch, ev);
                first = !delivery.calledProvider() && first;
                done.put(ev.id, delivery.result());

                if (delivery.pause()) {
                    paused = true;
                    pauseReason = PAUSE_MESSAGE;
                }
            }
        }

        List<BulkWhatsAppSendResponse.Result> results = new ArrayList<>();

        for (Long id : ids) {
            results.add(done.get(id));
        }

        return summarize(batch.getBatchId(), results, null, paused, pauseReason);
    }

    // ------------------------------------------------------------ batch results

    // The authoritative, reload-safe view of the WHOLE batch: every originally
    // selected record, in order, with its recorded outcome and delivery status -
    // or, if nothing was attempted yet, whether it is still eligible. Readable by
    // the batch's owner and by an admin.
    public BulkWhatsAppSendResponse results(String batchId) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, WHATSAPP_SEND);

        WhatsAppBulkBatch batch = loadBatch(batchId, employee, true);

        boolean owner = employee.getId().equals(batch.getOwnerEmployeeId());

        List<WhatsAppSendLog> logged = sendLogRepository.findByBatchIdOrderByIdAsc(batch.getBatchId());

        // Delivery events that raced ahead of their send-log row are applied now.
        List<String> messageIds = logged.stream().map(WhatsAppSendLog::getProviderMessageId)
                .filter(Objects::nonNull).toList();

        if (webhookService.applyPending(messageIds) > 0) {
            logged = sendLogRepository.findByBatchIdOrderByIdAsc(batch.getBatchId());
        }

        Map<Long, WhatsAppSendLog> rows = new HashMap<>();

        for (WhatsAppSendLog row : logged) {
            if (row.getTarget() == batch.getTarget()) {
                rows.put(row.getTargetId(), row);
            }
        }

        List<Long> ids = batch.recipientIdList();

        // Live eligibility only for records with no recorded outcome, and only for
        // the owner (an admin viewing someone else's batch is not evaluated with
        // the admin's own scope).
        Map<Long, Eval> live = new HashMap<>();

        if (owner) {

            List<Long> unlogged = ids.stream().filter(id -> !rows.containsKey(id)).toList();

            for (Eval ev : evaluate(employee, batch.getTarget(), unlogged, batch.getBody(), batch.getBatchId())) {
                live.put(ev.id, ev);
            }
        }

        Set<String> alreadyUsed = rows.values().stream()
                .filter(r -> ACTIVE.contains(effective(r)))
                .map(WhatsAppSendLog::getPhoneE164)
                .collect(Collectors.toSet());

        List<BulkWhatsAppSendResponse.Result> results = new ArrayList<>();

        for (Long id : ids) {

            WhatsAppSendLog row = rows.get(id);

            if (row != null) {
                results.add(fromRow(row, effective(row).name(), false));
                continue;
            }

            Eval ev = live.get(id);

            if (ev == null) {
                results.add(new BulkWhatsAppSendResponse.Result(
                        id, null, null, "NOT_ATTEMPTED", "Not sent yet.", false, null, null, null));
            } else if (!ev.dto.isEligible()) {
                results.add(excluded(ev, ev.dto.getExclusionCode(), ev.dto.getExclusionMessage()));
            } else if (alreadyUsed.contains(ev.phone)) {
                results.add(excluded(ev, "DUPLICATE_PHONE",
                        "Another record in this batch already has this WhatsApp number - one message per number."));
            } else {
                results.add(new BulkWhatsAppSendResponse.Result(id, ev.dto.getCompanyName(), ev.dto.getPhoneMasked(),
                        "NOT_ATTEMPTED", "Not sent yet.", false, null, null, null));
            }
        }

        return summarize(batch.getBatchId(), results, info(batch, true), false, null);
    }

    // ----------------------------------------------------------------- internals

    private record Delivery(BulkWhatsAppSendResponse.Result result, boolean pause, boolean calledProvider) {
    }

    private WhatsAppBulkBatch loadBatch(String batchId, Employee employee, boolean allowAdmin) {

        if (batchId == null || !BATCH_ID.matcher(batchId).matches()) {
            throw new IllegalArgumentException("A valid batch id is required.");
        }

        WhatsAppBulkBatch batch = batchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bulk WhatsApp batch not found"));

        if (!employee.getId().equals(batch.getOwnerEmployeeId())
                && !(allowAdmin && currentUserService.isAdmin())) {
            throw new AccessDeniedException("This batch belongs to another user.");
        }

        return batch;
    }

    // A chunk may restate target/template, but only to prove it is the same
    // batch - never to change it.
    private void verifyUnchanged(WhatsAppBulkBatch batch, BulkWhatsAppRequest request) {

        if (request.getTarget() != null && request.getTarget() != batch.getTarget()) {
            throw new IllegalArgumentException("This batch was created for a different type of record.");
        }

        if (request.getTemplateId() != null && !request.getTemplateId().equals(batch.getTemplateId())) {
            throw new IllegalArgumentException(
                    "This batch uses a different template. Create a new batch to use another template.");
        }
    }

    // The recorded answer for a recipient, or null when it may be (re)attempted
    // (a confirmed FAILED).
    private BulkWhatsAppSendResponse.Result replay(WhatsAppSendLog row) {

        WhatsAppSendStatus status = effective(row);

        switch (status) {

            case ACCEPTED:
                return fromRow(row, "ACCEPTED", true);

            case UNKNOWN:
                if (row.getStatus() == WhatsAppSendStatus.PENDING) {
                    // Persist the stale-PENDING -> UNKNOWN conclusion, then report it.
                    sendLogRepository.transition(row.getId(), WhatsAppSendStatus.PENDING, WhatsAppSendStatus.UNKNOWN,
                            "Sending was interrupted - outcome unknown", LocalDateTime.now());
                }
                return fromRow(row, "UNKNOWN", true);

            case PENDING:
                return fromRow(row, "IN_PROGRESS", true);

            default:
                return null;
        }
    }

    private Delivery deliver(Employee employee, WhatsAppBulkBatch batch, Eval ev) {

        String batchId = batch.getBatchId();
        String phone = ev.phone;

        Optional<WhatsAppSendLog> existing = sendLogRepository.findByBatchIdAndTargetAndTargetId(
                batchId, batch.getTarget(), ev.id);

        WhatsAppSendLog row;
        boolean retried = false;

        if (existing.isPresent()) {

            row = existing.get();

            // Only a confirmed FAILED may be retried - and only one request may win it.
            if (sendLogRepository.transition(row.getId(), WhatsAppSendStatus.FAILED, WhatsAppSendStatus.PENDING,
                    null, LocalDateTime.now()) != 1) {

                BulkWhatsAppSendResponse.Result recorded = sendLogRepository.findById(row.getId())
                        .map(this::replay).orElse(null);

                return new Delivery(recorded != null ? recorded : fromRow(row, "IN_PROGRESS", true), false, false);
            }

            row = sendLogRepository.findById(row.getId()).orElse(row);
            row.setPhoneE164(phone);
            row.setRecordLabel(label(ev));
            retried = true;

        } else {

            row = WhatsAppSendLog.builder()
                    .batchId(batchId)
                    .target(batch.getTarget())
                    .targetId(ev.id)
                    .recordLabel(label(ev))
                    .phoneE164(phone)
                    .status(WhatsAppSendStatus.PENDING)
                    .templateId(batch.getTemplateId())
                    .templateVersion(batch.getTemplateVersion())
                    .sentByEmployeeId(employee.getId())
                    .build();

            try {
                row = sendLogRepository.saveAndFlush(row);
            } catch (DataIntegrityViolationException e) {
                // A concurrent request claimed this recipient first.
                return new Delivery(result(ev, "IN_PROGRESS", "Already being sent by another request.", true),
                        false, false);
            }
        }

        // Checked AGAIN now that the claim exists, immediately before the provider call:
        //  - consent (an opt-out recorded since the preview - e.g. an inbound STOP - wins);
        //  - an earlier claim on the same number (another batch, or another record of
        //    this batch, claiming at the same instant): the lowest claim id wins.
        WhatsAppConsentStatus consent = consentService.status(phone).orElse(null);

        if (consent != WhatsAppConsentStatus.OPTED_IN) {
            release(row, retried, "No WhatsApp opt-in on record at send time");
            return consent == WhatsAppConsentStatus.OPTED_OUT
                    ? new Delivery(excluded(ev, "OPTED_OUT", OPTED_OUT_MESSAGE), false, false)
                    : new Delivery(excluded(ev, "NO_OPT_IN", NO_OPT_IN_MESSAGE), false, false);
        }

        if (sendLogRepository.countEarlierActiveClaims(phone, row.getId(), ACTIVE, windowStart()) > 0) {
            release(row, retried, "Another attempt to this number is recorded");
            return new Delivery(excluded(ev, "RECENTLY_MESSAGED", recentMessage()), false, false);
        }

        WhatsAppClient.SendOutcome outcome;

        try {
            outcome = whatsAppClient.sendText(phone, ev.dto.getRenderedText());
        } catch (RuntimeException e) {
            log.warn("Bulk WhatsApp: unexpected error while sending, outcome unknown ({})", e.getClass().getSimpleName());
            outcome = new WhatsAppClient.SendOutcome(WhatsAppClient.Result.UNKNOWN, null, null,
                    "Unexpected error while sending", true);
        }

        WhatsAppSendStatus status = switch (outcome.result()) {
            case ACCEPTED -> WhatsAppSendStatus.ACCEPTED;
            case FAILED -> WhatsAppSendStatus.FAILED;
            case UNKNOWN -> WhatsAppSendStatus.UNKNOWN;
        };

        row.setStatus(status);
        row.setDetail(truncate(outcome.detail(), 500));
        row.setProviderMessageId(status == WhatsAppSendStatus.ACCEPTED ? outcome.providerMessageId() : null);
        row.setUpdatedAt(LocalDateTime.now());

        try {
            sendLogRepository.saveAndFlush(row);
        } catch (RuntimeException e) {
            // The send already happened; the claim stays PENDING and will read as UNKNOWN.
            log.error("Bulk WhatsApp: could not record the outcome for batch {} ({})", batchId,
                    e.getClass().getSimpleName());
        }

        if (status == WhatsAppSendStatus.ACCEPTED) {

            logActivity(employee, batch.getTarget(), ev);

            if (outcome.providerMessageId() != null) {
                // A delivery webhook may have arrived before this row was written.
                webhookService.applyPending(List.of(outcome.providerMessageId()));
            }
        }

        BulkWhatsAppSendResponse.Result result = result(ev, status.name(), outcome.detail(), false);

        if (status == WhatsAppSendStatus.ACCEPTED && outcome.providerMessageId() != null) {
            sendLogRepository.findById(row.getId()).ifPresent(r -> {
                result.setDeliveryStatus(r.getDeliveryStatus() != null ? r.getDeliveryStatus().name() : null);
                result.setDeliveryUpdatedAt(r.getDeliveryUpdatedAt());
            });
        }

        return new Delivery(result, outcome.pauseSending(), true);
    }

    // Nothing was sent: a fresh claim is removed; a retried FAILED goes back to FAILED.
    private void release(WhatsAppSendLog row, boolean retried, String detail) {

        try {
            if (retried) {
                sendLogRepository.transition(row.getId(), WhatsAppSendStatus.PENDING, WhatsAppSendStatus.FAILED,
                        detail, LocalDateTime.now());
            } else {
                sendLogRepository.deleteById(row.getId());
            }
        } catch (RuntimeException e) {
            // Worst case the claim ages into UNKNOWN - never a silent send.
            log.warn("Bulk WhatsApp: could not release a claim ({})", e.getClass().getSimpleName());
        }
    }

    private void logActivity(Employee employee, BulkEmailTarget target, Eval ev) {

        String text = "Bulk WhatsApp message accepted by the WhatsApp provider for " + ev.dto.getPhoneMasked()
                + " (accepted for sending - not proof of delivery)";

        try {

            if (target == BulkEmailTarget.OPPORTUNITY) {
                activityLogService.log(employee, ActivityModule.OPPORTUNITY, ActivityAction.WHATSAPP_SENT,
                        ev.opportunity.getId(), ev.opportunity.getTitle(), text);
            } else {
                activityLogService.log(employee, ActivityModule.LEAD, ActivityAction.WHATSAPP_SENT,
                        ev.lead.getId(), ev.lead.getCompanyName(), text);
            }

        } catch (RuntimeException e) {
            log.warn("Bulk WhatsApp: activity log entry failed ({})", e.getClass().getSimpleName());
        }
    }

    // Same number already claimed / sent by ANOTHER record of this batch (earlier chunk).
    private boolean duplicateInBatch(WhatsAppBulkBatch batch, Eval ev) {

        return sendLogRepository.findByBatchIdAndPhoneE164AndStatusIn(batch.getBatchId(), ev.phone, ACTIVE)
                .stream()
                .anyMatch(l -> !(l.getTarget() == batch.getTarget() && l.getTargetId().equals(ev.id)));
    }

    private WhatsAppSendStatus effective(WhatsAppSendLog row) {

        if (row.getStatus() == WhatsAppSendStatus.PENDING
                && row.getUpdatedAt() != null
                && row.getUpdatedAt().isBefore(LocalDateTime.now().minus(PENDING_STALE_AFTER))) {
            return WhatsAppSendStatus.UNKNOWN;
        }

        return row.getStatus();
    }

    private void pace() {

        if (minIntervalMs <= 0) {
            return;
        }

        try {
            Thread.sleep(minIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LocalDateTime windowStart() {
        return LocalDateTime.now().minusHours(dedupeHours);
    }

    private String recentMessage() {
        return "This number (or record) was already messaged on WhatsApp - or may have been - in the last "
                + dedupeHours + " hours.";
    }

    private static String providerMessage(boolean configured, String status) {

        if (!configured) {
            return "WhatsApp sending is not configured on this server. Please contact an administrator.";
        }

        if (!"WORKING".equals(status)) {
            return "WhatsApp is not connected (session status: " + status + "). "
                    + "An administrator must link or restart the WhatsApp session before messages can be sent.";
        }

        return null;
    }

    private static final String OPTED_OUT_MESSAGE = "This number has opted out of WhatsApp messages.";
    private static final String NO_OPT_IN_MESSAGE = "No WhatsApp opt-in is recorded for this number.";

    // ------------------------------------------------------------ evaluation

    private static final class Eval {

        final Long id;
        Lead lead;
        Opportunity opportunity;
        String phone;      // normalized digits incl. country code, when valid
        final BulkWhatsAppPreviewResponse.Recipient dto = new BulkWhatsAppPreviewResponse.Recipient();

        Eval(Long id) {
            this.id = id;
            dto.setId(id);
            dto.setWarnings(new ArrayList<>());
            dto.setEligible(true);
        }

        void exclude(String code, String message) {
            dto.setEligible(false);
            dto.setExclusionCode(code);
            dto.setExclusionMessage(message);
        }
    }

    // Loads, authorizes and validates every requested record for THIS caller.
    // body null = recipient check only (nothing rendered).
    // ownBatchId: attempts by this batch are not "recent attempts by another batch".
    private List<Eval> evaluate(Employee employee, BulkEmailTarget target, List<Long> ids,
                                String body, String ownBatchId) {

        List<Eval> evals = new ArrayList<>();

        // Pass 1: load, authorize, validate the number.
        for (Long id : ids) {

            Eval ev = new Eval(id);

            Lead lead;
            Opportunity opportunity = null;

            if (target == BulkEmailTarget.OPPORTUNITY) {
                opportunity = opportunityRepository.findById(id).orElse(null);
                lead = opportunity != null ? opportunity.getLead() : null;
            } else {
                lead = leadRepository.findById(id).orElse(null);
            }

            // Missing and forbidden are deliberately indistinguishable, and
            // nothing about the record is revealed.
            if (lead == null || !mayMessage(employee, target, lead)) {
                ev.exclude("NOT_AVAILABLE", "This record was not found or you do not have access to it.");
                evals.add(ev);
                continue;
            }

            ev.lead = lead;
            ev.opportunity = opportunity;

            // An Opportunity is messaged at its OWN contact (V14) - the same contact
            // its screens and merge fields use. No fallback to the lead's number.
            String contactPerson = opportunity != null ? opportunity.getContactPerson() : lead.getContactPerson();
            String raw = opportunity != null ? opportunity.getContactPhone() : lead.getPhone();

            ev.dto.setCompanyName(lead.getCompanyName());
            ev.dto.setContactPerson(contactPerson);

            if (raw == null || raw.isBlank()) {
                ev.exclude("NO_PHONE", "No phone number on file.");
            } else {
                ev.phone = consentService.normalize(raw);
                if (ev.phone == null) {
                    ev.exclude("INVALID_PHONE",
                            "The phone number on file is not a valid mobile number for WhatsApp (landline, malformed or another country).");
                } else {
                    ev.dto.setPhoneMasked(consentService.mask(ev.phone));
                }
            }

            if (lead.getLeadStatus() == LeadStatus.INVALID) {
                ev.dto.getWarnings().add("The lead is marked INVALID.");
            }

            evals.add(ev);
        }

        // Pass 2: consent and recent attempts - one query each for the whole list.
        List<Eval> candidates = evals.stream().filter(e -> e.dto.isEligible()).toList();

        if (!candidates.isEmpty()) {

            List<String> phones = candidates.stream().map(e -> e.phone).distinct().toList();
            List<Long> recordIds = candidates.stream().map(e -> e.id).toList();
            LocalDateTime since = windowStart();

            Map<String, WhatsAppConsentStatus> consent = consentService.statuses(phones);

            Set<String> recentPhones = new HashSet<>(
                    sendLogRepository.findRecentlyActivePhones(phones, ACTIVE, since, ownBatchId));

            Set<Long> recentRecords = new HashSet<>(
                    sendLogRepository.findRecentlyActiveRecordIds(target, recordIds, ACTIVE, since, ownBatchId));

            for (Eval ev : candidates) {

                WhatsAppConsentStatus status = consent.get(ev.phone);

                if (status == WhatsAppConsentStatus.OPTED_OUT) {
                    ev.exclude("OPTED_OUT", OPTED_OUT_MESSAGE);
                } else if (status != WhatsAppConsentStatus.OPTED_IN) {
                    ev.exclude("NO_OPT_IN", NO_OPT_IN_MESSAGE);
                } else if (recentPhones.contains(ev.phone) || recentRecords.contains(ev.id)) {
                    ev.exclude("RECENTLY_MESSAGED", recentMessage());
                }
            }
        }

        // Pass 3: render each remaining recipient's own message (server-side, the
        // same renderer for preview and send).
        if (body != null) {

            for (Eval ev : evals) {

                if (!ev.dto.isEligible()) {
                    continue;
                }

                EmailPlaceholderRenderer.Rendered r = WhatsAppTemplateRenderer.render(body, ev.lead, ev.opportunity);

                if (!r.issues().isEmpty()) {
                    ev.exclude("UNRESOLVED_VARIABLE", String.join("; ", r.issues()));
                } else {
                    ev.dto.setRenderedText(r.text());
                }
            }
        }

        // Pass 4: one message per distinct number. Not silent: later records are
        // excluded with a visible reason, the first is annotated.
        Map<String, Eval> firstByPhone = new HashMap<>();

        for (Eval ev : evals) {

            if (!ev.dto.isEligible()) {
                continue;
            }

            Eval first = firstByPhone.putIfAbsent(ev.phone, ev);

            if (first != null) {

                ev.exclude("DUPLICATE_PHONE",
                        "Same WhatsApp number as " + display(first) + " (already included) - one message is sent per number.");

                String note = "Other selected records share this number - only this one will be messaged.";

                if (!first.dto.getWarnings().contains(note)) {
                    first.dto.getWarnings().add(note);
                }
            }
        }

        return evals;
    }

    private boolean mayMessage(Employee employee, BulkEmailTarget target, Lead lead) {

        Long owner = lead.getAssignedEmployee() != null ? lead.getAssignedEmployee().getId() : null;

        try {

            accessControlService.assertCanAccess(employee, WHATSAPP_SEND, owner);

            if (target == BulkEmailTarget.OPPORTUNITY) {
                accessControlService.assertCanAccess(employee, OPPORTUNITY_VIEW, owner);
            }

            return true;

        } catch (AccessDeniedException e) {
            return false;
        }
    }

    // -------------------------------------------------------------- helpers

    private void requireTarget(BulkEmailTarget target) {

        if (target == null) {
            throw new IllegalArgumentException("Target (LEAD or OPPORTUNITY) is required.");
        }
    }

    private List<Long> distinctIds(List<Long> ids, int max) {

        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Select at least one record.");
        }

        Set<Long> distinct = new LinkedHashSet<>();

        for (Long id : ids) {

            if (id == null || id <= 0) {
                throw new IllegalArgumentException("Invalid record id in the selection.");
            }

            distinct.add(id);
        }

        if (distinct.size() > max) {
            throw new IllegalArgumentException("At most " + max + " records can be handled in one request.");
        }

        return new ArrayList<>(distinct);
    }

    private static String hash(String body) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private BulkWhatsAppBatchResponse info(WhatsAppBulkBatch batch, boolean withContent) {
        return new BulkWhatsAppBatchResponse(
                batch.getBatchId(), batch.getTarget(), batch.getTemplateId(), batch.getTemplateVersion(),
                batch.getTemplateName(), batch.getRecipientCount(), maxChunk, maxRecipients, batch.getCreatedAt(),
                withContent ? batch.getBody() : null,
                withContent ? batch.recipientIdList() : null);
    }

    private BulkWhatsAppSendResponse.Result result(Eval ev, String outcome, String detail, boolean already) {
        return new BulkWhatsAppSendResponse.Result(
                ev.id, ev.dto.getCompanyName(), ev.dto.getPhoneMasked(), outcome, detail, already, null, null, null);
    }

    private BulkWhatsAppSendResponse.Result excluded(Eval ev, String code, String message) {
        return new BulkWhatsAppSendResponse.Result(
                ev.id, ev.dto.getCompanyName(), ev.dto.getPhoneMasked(), "EXCLUDED", message, false, code, null, null);
    }

    private BulkWhatsAppSendResponse.Result fromRow(WhatsAppSendLog row, String outcome, boolean already) {
        return new BulkWhatsAppSendResponse.Result(
                row.getTargetId(), row.getRecordLabel(), consentService.mask(row.getPhoneE164()), outcome,
                row.getDetail(), already, null,
                row.getDeliveryStatus() != null ? row.getDeliveryStatus().name() : null,
                row.getDeliveryUpdatedAt());
    }

    private BulkWhatsAppSendResponse summarize(String batchId, List<BulkWhatsAppSendResponse.Result> results,
                                               BulkWhatsAppBatchResponse batch, boolean paused, String pauseReason) {

        int accepted = 0, failed = 0, unknown = 0, inProgress = 0, excluded = 0, notAttempted = 0;

        for (BulkWhatsAppSendResponse.Result r : results) {
            switch (r.getOutcome()) {
                case "ACCEPTED" -> accepted++;
                case "FAILED" -> failed++;
                case "UNKNOWN" -> unknown++;
                case "IN_PROGRESS", "PENDING" -> inProgress++;
                case "NOT_ATTEMPTED" -> notAttempted++;
                default -> excluded++;
            }
        }

        return new BulkWhatsAppSendResponse(batchId, accepted, failed, unknown, inProgress, excluded, notAttempted,
                paused, pauseReason, results, batch);
    }

    private String label(Eval ev) {
        String label = ev.opportunity != null && ev.opportunity.getTitle() != null
                ? ev.lead.getCompanyName() + " - " + ev.opportunity.getTitle()
                : ev.lead.getCompanyName();
        return truncate(label, 255);
    }

    private String display(Eval ev) {
        return ev.dto.getCompanyName() != null ? ev.dto.getCompanyName() : "record #" + ev.id;
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
