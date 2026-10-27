package com.compact.crm.service;

import com.compact.crm.dto.request.BulkEmailRequest;
import com.compact.crm.dto.response.BulkEmailBatchResponse;
import com.compact.crm.dto.response.BulkEmailPreviewResponse;
import com.compact.crm.dto.response.BulkEmailSendResponse;
import com.compact.crm.entity.EmailBulkBatch;
import com.compact.crm.entity.EmailSendLog;
import com.compact.crm.entity.EmailTemplate;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.EmailSendStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.EmailBulkBatchRepository;
import com.compact.crm.repository.EmailSendLogRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.compact.crm.security.AccessControlService.EMAIL_SEND;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;

// Bulk email for Leads and Opportunities: one INDIVIDUAL email per recipient
// (never a shared To/CC), through the existing BrevoEmailClient and activity
// log, with the same EMAIL_SEND scope the single-recipient flow uses.
//
// Sending model:
//  - A BATCH is created first (POST /batches). The SERVER issues its id and pins
//    the owner, target, template, the exact subject/body (+ hash) and the ordered
//    list of recipient record ids. The 100-record cap applies to that whole list.
//  - The browser then sends the batch in CHUNKS of at most `max-chunk` (10)
//    records. Every chunk must be a subset of the pinned list and can only ever
//    send the pinned content: a request cannot add recipients or change the
//    message, and another user cannot use or read the batch. A synchronous request
//    for a whole batch would run into the proxy/browser timeout on the free-tier
//    instance, and an ambiguous timeout is precisely what must not happen
//    mid-batch. No queue/worker infrastructure exists, and a modest batch does not
//    warrant adding any.
//  - /preview (and every chunk again, immediately before sending) re-validates and
//    re-authorizes each record against the CURRENT data and the caller's own scope.
//  - A recipient whose outcome is already recorded is answered from that record
//    FIRST, before the (mutable) lead is looked at again: a reassigned or edited
//    lead can never turn an ACCEPTED email into "excluded".
//  - Each recipient is claimed in email_send_log (unique per batch+record) before
//    the provider is called, so a double click, a repeated request or a retried
//    chunk can never send the same recipient twice.
//  - The same address/record is not emailed again by a NEW batch while an
//    ACCEPTED, UNKNOWN or in-flight attempt is recorded inside the de-duplication
//    window (`crm.mail.bulk.dedupe-hours`, default 24). Only a confirmed FAILED
//    can be retried. There is deliberately no "send again anyway" action: an
//    UNKNOWN outcome can mean "delivered", and no confirmation dialog can prove it
//    was not.
//  - Addresses on the do-not-email list (EmailSuppressionService) are excluded at
//    preview and again right before the provider call.
//  - Outcomes: ACCEPTED (provider 2xx - not proof of delivery), FAILED (a 4xx:
//    definitely not sent, may be retried), UNKNOWN (timeout / 5xx / I-O error: may
//    have been sent - NEVER resent). A claim abandoned mid-send is UNKNOWN too.
//
// Not @Transactional on purpose: provider calls must not run inside an open DB
// transaction, and each recipient's outcome is independent.
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkEmailService {

    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    // A PENDING claim older than this means the process died (or the final DB
    // write failed) after we may have called the provider: outcome UNKNOWN.
    private static final Duration PENDING_STALE_AFTER = Duration.ofMinutes(2);

    // Attempts that may have reached (or still be reaching) the customer.
    private static final List<EmailSendStatus> ACTIVE =
            List.of(EmailSendStatus.ACCEPTED, EmailSendStatus.UNKNOWN, EmailSendStatus.PENDING);

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final EmailSendLogRepository sendLogRepository;
    private final EmailBulkBatchRepository batchRepository;
    private final EmailTemplateService emailTemplateService;
    private final EmailSuppressionService suppressionService;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;
    private final ActivityLogService activityLogService;
    private final BrevoEmailClient brevoEmailClient;

    @Value("${crm.mail.bulk.max-recipients:100}")
    private int maxRecipients = 100;

    @Value("${crm.mail.bulk.max-chunk:10}")
    private int maxChunk = 10;

    @Value("${crm.mail.bulk.dedupe-hours:24}")
    private int dedupeHours = 24;

    // ------------------------------------------------------------------ preview

    public BulkEmailPreviewResponse preview(BulkEmailRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SEND);

        requireTarget(request.getTarget());

        List<Long> ids = distinctIds(request.getIds(), maxRecipients);

        List<Eval> evals = evaluate(employee, request.getTarget(), ids, request.getSubject(), request.getBody(), "");

        List<BulkEmailPreviewResponse.Recipient> recipients = evals.stream().map(e -> e.dto).toList();
        long eligible = recipients.stream().filter(BulkEmailPreviewResponse.Recipient::isEligible).count();

        return new BulkEmailPreviewResponse(
                request.getTarget(), ids.size(), (int) eligible, (int) (recipients.size() - eligible), recipients);
    }

    // ------------------------------------------------------------ create batch

    // Pins owner + target + template + content + the WHOLE recipient list. The
    // batch id is a server-generated UUID, so it cannot collide with (or be
    // guessed into) anyone else's batch.
    public BulkEmailBatchResponse createBatch(BulkEmailRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SEND);

        requireTarget(request.getTarget());

        if (isBlank(request.getSubject()) || isBlank(request.getBody())) {
            throw new IllegalArgumentException("Subject and message are required.");
        }

        List<Long> ids = distinctIds(request.getIds(), maxRecipients);

        if (request.getTemplateId() != null) {

            EmailTemplate template = emailTemplateService.getById(request.getTemplateId());

            if (!Boolean.TRUE.equals(template.getIsActive())) {
                throw new IllegalArgumentException("That email template is no longer active.");
            }
        }

        EmailBulkBatch saved = batchRepository.save(EmailBulkBatch.builder()
                .batchId(UUID.randomUUID().toString())
                .ownerEmployeeId(employee.getId())
                .target(request.getTarget())
                .templateId(request.getTemplateId())
                .subject(request.getSubject())
                .body(request.getBody())
                .contentHash(hash(request.getSubject(), request.getBody()))
                .recipientIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .recipientCount(ids.size())
                .build());

        return info(saved, false);
    }

    // --------------------------------------------------------------------- send

    // Sends one chunk of an existing batch. Safe to repeat: recipients already
    // handled are answered from the log and never sent again.
    public BulkEmailSendResponse send(BulkEmailRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SEND);

        EmailBulkBatch batch = loadBatch(request.getBatchId(), employee, false);

        verifyUnchanged(batch, request);

        List<Long> ids = distinctIds(request.getIds(), maxChunk);

        if (!new HashSet<>(batch.recipientIdList()).containsAll(ids)) {
            throw new IllegalArgumentException("The request contains records that are not part of this batch.");
        }

        Map<Long, BulkEmailSendResponse.Result> done = new HashMap<>();
        List<Long> pending = new ArrayList<>();

        for (Long id : ids) {

            Optional<EmailSendLog> existing = sendLogRepository.findByBatchIdAndTargetAndTargetId(
                    batch.getBatchId(), batch.getTarget(), id);

            // Recorded outcomes are answered BEFORE the live record is looked at.
            BulkEmailSendResponse.Result replay = existing.map(this::replay).orElse(null);

            if (replay != null) {
                done.put(id, replay);
            } else {
                pending.add(id);
            }
        }

        // Revalidated NOW, not trusted from the earlier preview.
        List<Eval> evals = evaluate(employee, batch.getTarget(), pending,
                batch.getSubject(), batch.getBody(), batch.getBatchId());

        for (Eval ev : evals) {

            if (!ev.dto.isEligible()) {
                done.put(ev.id, excluded(ev, ev.dto.getExclusionCode(), ev.dto.getExclusionMessage()));
                continue;
            }

            if (duplicateInBatch(batch, ev)) {
                done.put(ev.id, excluded(ev, "DUPLICATE_EMAIL",
                        "Another record in this batch already has this email address - one email per address."));
                continue;
            }

            done.put(ev.id, deliver(employee, batch, ev));
        }

        List<BulkEmailSendResponse.Result> results = new ArrayList<>();

        for (Long id : ids) {
            results.add(done.get(id));
        }

        return summarize(batch.getBatchId(), results, null);
    }

    // ------------------------------------------------------------ batch results

    // The authoritative, reload-safe view of the WHOLE batch: every originally
    // selected record, in order, with its recorded outcome - or, if nothing has
    // been attempted yet, whether it is still eligible. Readable by the batch's
    // owner and by an admin.
    public BulkEmailSendResponse results(String batchId) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SEND);

        EmailBulkBatch batch = loadBatch(batchId, employee, true);

        boolean owner = employee.getId().equals(batch.getOwnerEmployeeId());

        Map<Long, EmailSendLog> rows = new HashMap<>();

        for (EmailSendLog row : sendLogRepository.findByBatchIdOrderByIdAsc(batch.getBatchId())) {
            if (row.getTarget() == batch.getTarget()) {
                rows.put(row.getTargetId(), row);
            }
        }

        List<Long> ids = batch.recipientIdList();

        // Live eligibility only for records with no recorded outcome, and only for
        // the owner (an admin viewing someone else's batch is not evaluated with the
        // admin's own scope).
        Map<Long, Eval> live = new HashMap<>();

        if (owner) {

            List<Long> unlogged = ids.stream().filter(id -> !rows.containsKey(id)).toList();

            for (Eval ev : evaluate(employee, batch.getTarget(), unlogged,
                    batch.getSubject(), batch.getBody(), batch.getBatchId())) {
                live.put(ev.id, ev);
            }
        }

        Set<String> alreadyUsed = rows.values().stream()
                .filter(r -> ACTIVE.contains(effective(r)))
                .map(EmailSendLog::getRecipientEmail)
                .collect(Collectors.toSet());

        List<BulkEmailSendResponse.Result> results = new ArrayList<>();

        for (Long id : ids) {

            EmailSendLog row = rows.get(id);

            if (row != null) {
                // A snapshot of the batch, not a replay of a request: nothing is "already processed" here.
                results.add(fromRow(row, effective(row).name(), false));
                continue;
            }

            Eval ev = live.get(id);

            if (ev == null) {
                results.add(new BulkEmailSendResponse.Result(
                        id, null, null, "NOT_ATTEMPTED", "Not sent yet.", false, null));
            } else if (!ev.dto.isEligible()) {
                results.add(excluded(ev, ev.dto.getExclusionCode(), ev.dto.getExclusionMessage()));
            } else if (alreadyUsed.contains(ev.email)) {
                results.add(excluded(ev, "DUPLICATE_EMAIL",
                        "Another record in this batch already has this email address - one email per address."));
            } else {
                results.add(new BulkEmailSendResponse.Result(
                        id, ev.dto.getCompanyName(), ev.dto.getEmail(), "NOT_ATTEMPTED", "Not sent yet.", false, null));
            }
        }

        return summarize(batch.getBatchId(), results, info(batch, true));
    }

    // ----------------------------------------------------------------- internals

    private EmailBulkBatch loadBatch(String batchId, Employee employee, boolean allowAdmin) {

        if (batchId == null || !BATCH_ID.matcher(batchId).matches()) {
            throw new IllegalArgumentException("A valid batch id is required.");
        }

        EmailBulkBatch batch = batchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bulk email batch not found"));

        if (!employee.getId().equals(batch.getOwnerEmployeeId())
                && !(allowAdmin && currentUserService.isAdmin())) {
            throw new AccessDeniedException("This batch belongs to another user.");
        }

        return batch;
    }

    // A chunk may restate target/template/subject/body, but only to prove it is the
    // same batch - never to change it.
    private void verifyUnchanged(EmailBulkBatch batch, BulkEmailRequest request) {

        if (request.getTarget() != null && request.getTarget() != batch.getTarget()) {
            throw new IllegalArgumentException("This batch was created for a different type of record.");
        }

        if (request.getTemplateId() != null && !request.getTemplateId().equals(batch.getTemplateId())) {
            throw new IllegalArgumentException(
                    "This batch uses a different template. Create a new batch to use another template.");
        }

        if (request.getSubject() != null || request.getBody() != null) {

            String claimed = hash(
                    request.getSubject() == null ? "" : request.getSubject(),
                    request.getBody() == null ? "" : request.getBody());

            if (!claimed.equals(batch.getContentHash())) {
                throw new IllegalArgumentException(
                        "The message differs from the one this batch was created with. "
                                + "Create a new batch to send different content.");
            }
        }
    }

    // The recorded answer for a recipient, or null when it may be (re)attempted
    // (no usable record, or a confirmed FAILED).
    private BulkEmailSendResponse.Result replay(EmailSendLog row) {

        EmailSendStatus status = effective(row);

        switch (status) {

            case ACCEPTED:
                return fromRow(row, "ACCEPTED", true);

            case UNKNOWN:
                if (row.getStatus() == EmailSendStatus.PENDING) {
                    // Persist the stale-PENDING -> UNKNOWN conclusion, then report it.
                    sendLogRepository.transition(row.getId(), EmailSendStatus.PENDING, EmailSendStatus.UNKNOWN,
                            "Sending was interrupted - outcome unknown", LocalDateTime.now());
                }
                return fromRow(row, "UNKNOWN", true);

            case PENDING:
                return fromRow(row, "IN_PROGRESS", true);

            default:
                return null;
        }
    }

    private BulkEmailSendResponse.Result deliver(Employee employee, EmailBulkBatch batch, Eval ev) {

        String batchId = batch.getBatchId();
        String email = ev.email;

        Optional<EmailSendLog> existing = sendLogRepository.findByBatchIdAndTargetAndTargetId(
                batchId, batch.getTarget(), ev.id);

        EmailSendLog row;
        boolean retried = false;

        if (existing.isPresent()) {

            row = existing.get();

            // Only a confirmed FAILED may be retried - and only one request may win it.
            if (sendLogRepository.transition(row.getId(), EmailSendStatus.FAILED, EmailSendStatus.PENDING,
                    null, LocalDateTime.now()) != 1) {

                BulkEmailSendResponse.Result recorded = sendLogRepository.findById(row.getId())
                        .map(this::replay).orElse(null);

                return recorded != null
                        ? recorded
                        : fromRow(row, "IN_PROGRESS", true);
            }

            row = sendLogRepository.findById(row.getId()).orElse(row);
            row.setRecipientEmail(email);
            row.setRecordLabel(label(ev));
            retried = true;

        } else {

            row = EmailSendLog.builder()
                    .batchId(batchId)
                    .target(batch.getTarget())
                    .targetId(ev.id)
                    .recordLabel(label(ev))
                    .recipientEmail(email)
                    .status(EmailSendStatus.PENDING)
                    .templateId(batch.getTemplateId())
                    .sentByEmployeeId(employee.getId())
                    .build();

            try {
                row = sendLogRepository.saveAndFlush(row);
            } catch (DataIntegrityViolationException e) {
                // A concurrent request claimed this recipient first.
                return result(ev, "IN_PROGRESS", "Already being sent by another request.", true);
            }
        }

        // Checked AGAIN now that the claim exists, immediately before the provider is called:
        //  - the do-not-email list (an entry added since the preview still wins);
        //  - an earlier claim on the same address (another batch, or another record of
        //    this batch, claiming at the same instant): the lowest claim id wins.
        if (suppressionService.isSuppressed(email)) {
            release(row, retried, "Address is on the do-not-email list");
            return excluded(ev, "SUPPRESSED", SUPPRESSED_MESSAGE);
        }

        if (sendLogRepository.countEarlierActiveClaims(
                email, row.getId(), ACTIVE, windowStart()) > 0) {
            release(row, retried, "Another attempt to this address is recorded");
            return excluded(ev, "RECENTLY_EMAILED", recentMessage());
        }

        BrevoEmailClient.SendOutcome outcome;

        try {
            outcome = brevoEmailClient.trySend(email, ev.dto.getRenderedSubject(), ev.dto.getRenderedBody(), List.of());
        } catch (RuntimeException e) {
            log.warn("Bulk email: unexpected error while sending, outcome unknown ({})", e.getClass().getSimpleName());
            outcome = new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.UNKNOWN, null, "Unexpected error while sending");
        }

        EmailSendStatus status = switch (outcome.result()) {
            case ACCEPTED -> EmailSendStatus.ACCEPTED;
            case FAILED -> EmailSendStatus.FAILED;
            case UNKNOWN -> EmailSendStatus.UNKNOWN;
        };

        row.setStatus(status);
        row.setDetail(outcome.detail());
        row.setUpdatedAt(LocalDateTime.now());

        try {
            sendLogRepository.save(row);
        } catch (RuntimeException e) {
            // The send already happened; the claim stays PENDING and will read as UNKNOWN.
            log.error("Bulk email: could not record the outcome for batch {} ({})", batchId, e.getClass().getSimpleName());
        }

        if (status == EmailSendStatus.ACCEPTED) {
            logActivity(employee, batch.getTarget(), ev, email);
        }

        return result(ev, status.name(), outcome.detail(), false);
    }

    // Nothing was sent: a fresh claim is removed; a retried FAILED goes back to FAILED.
    private void release(EmailSendLog row, boolean retried, String detail) {

        try {
            if (retried) {
                sendLogRepository.transition(row.getId(), EmailSendStatus.PENDING, EmailSendStatus.FAILED,
                        detail, LocalDateTime.now());
            } else {
                sendLogRepository.deleteById(row.getId());
            }
        } catch (RuntimeException e) {
            // Worst case the claim ages into UNKNOWN - never a silent send.
            log.warn("Bulk email: could not release a claim ({})", e.getClass().getSimpleName());
        }
    }

    private void logActivity(Employee employee, BulkEmailTarget target, Eval ev, String email) {

        try {

            if (target == BulkEmailTarget.OPPORTUNITY) {
                activityLogService.log(employee, ActivityModule.OPPORTUNITY, ActivityAction.EMAIL_SENT,
                        ev.opportunity.getId(), ev.opportunity.getTitle(),
                        "Bulk email accepted by the email provider for " + email);
            } else {
                activityLogService.log(employee, ActivityModule.LEAD, ActivityAction.EMAIL_SENT,
                        ev.lead.getId(), ev.lead.getCompanyName(),
                        "Bulk email accepted by the email provider for " + email);
            }

        } catch (RuntimeException e) {
            log.warn("Bulk email: activity log entry failed ({})", e.getClass().getSimpleName());
        }
    }

    // Same address already claimed/sent by ANOTHER record of this batch (earlier chunk).
    private boolean duplicateInBatch(EmailBulkBatch batch, Eval ev) {

        return sendLogRepository.findByBatchIdAndRecipientEmailAndStatusIn(
                        batch.getBatchId(), ev.email, ACTIVE)
                .stream()
                .anyMatch(l -> !(l.getTarget() == batch.getTarget() && l.getTargetId().equals(ev.id)));
    }

    private EmailSendStatus effective(EmailSendLog row) {

        if (row.getStatus() == EmailSendStatus.PENDING
                && row.getUpdatedAt() != null
                && row.getUpdatedAt().isBefore(LocalDateTime.now().minus(PENDING_STALE_AFTER))) {
            return EmailSendStatus.UNKNOWN;
        }

        return row.getStatus();
    }

    private LocalDateTime windowStart() {
        return LocalDateTime.now().minusHours(dedupeHours);
    }

    private String recentMessage() {
        return "This address (or record) was already emailed - or may have been - in the last "
                + dedupeHours + " hours.";
    }

    private static final String SUPPRESSED_MESSAGE = "This email address is on the do-not-email list.";

    // ------------------------------------------------------------ evaluation

    private static final class Eval {

        final Long id;
        Lead lead;
        Opportunity opportunity;
        String email;      // normalized (trimmed, lower-case) when present
        final BulkEmailPreviewResponse.Recipient dto = new BulkEmailPreviewResponse.Recipient();

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
    // ownBatchId: attempts recorded by this batch are not "recent attempts by
    // another batch" ("" for the batch-less preview).
    private List<Eval> evaluate(Employee employee, BulkEmailTarget target, List<Long> ids,
                                String subject, String body, String ownBatchId) {

        boolean render = !isBlank(subject) || !isBlank(body);

        List<Eval> evals = new ArrayList<>();

        // Pass 1: load, authorize, validate the address.
        for (Long id : ids) {

            Eval ev = new Eval(id);

            Lead lead = null;
            Opportunity opportunity = null;

            if (target == BulkEmailTarget.OPPORTUNITY) {
                opportunity = opportunityRepository.findById(id).orElse(null);
                lead = opportunity != null ? opportunity.getLead() : null;
            } else {
                lead = leadRepository.findById(id).orElse(null);
            }

            // Missing and forbidden are deliberately indistinguishable, and
            // nothing about the record is revealed.
            if (lead == null || !mayEmail(employee, target, lead)) {
                ev.exclude("NOT_AVAILABLE", "This record was not found or you do not have access to it.");
                evals.add(ev);
                continue;
            }

            ev.lead = lead;
            ev.opportunity = opportunity;

            ev.dto.setCompanyName(lead.getCompanyName());
            ev.dto.setContactPerson(lead.getContactPerson());

            String raw = lead.getEmail() == null ? "" : lead.getEmail().trim();
            ev.dto.setEmail(raw.isEmpty() ? null : raw);
            ev.email = EmailAddresses.normalize(raw);

            if (raw.isEmpty()) {
                ev.exclude("NO_EMAIL", "No email address on file.");
            } else if (!EmailAddresses.isValid(raw)) {
                ev.exclude("INVALID_EMAIL", "The email address on file is not valid.");
            }

            if (lead.getLeadStatus() == LeadStatus.INVALID) {
                ev.dto.getWarnings().add("The lead is marked INVALID.");
            }

            evals.add(ev);
        }

        // Pass 2: the do-not-email list and recent attempts - one query each for the whole list.
        List<Eval> candidates = evals.stream().filter(e -> e.dto.isEligible()).toList();

        if (!candidates.isEmpty()) {

            List<String> emails = candidates.stream().map(e -> e.email).distinct().toList();
            List<Long> recordIds = candidates.stream().map(e -> e.id).toList();
            LocalDateTime since = windowStart();

            Set<String> suppressed = suppressionService.findSuppressed(emails);

            Set<String> recentEmails = new HashSet<>(
                    sendLogRepository.findRecentlyActiveEmails(emails, ACTIVE, since, ownBatchId));

            Set<Long> recentRecords = new HashSet<>(
                    sendLogRepository.findRecentlyActiveRecordIds(target, recordIds, ACTIVE, since, ownBatchId));

            for (Eval ev : candidates) {

                if (suppressed.contains(ev.email)) {
                    ev.exclude("SUPPRESSED", SUPPRESSED_MESSAGE);
                } else if (recentEmails.contains(ev.email) || recentRecords.contains(ev.id)) {
                    ev.exclude("RECENTLY_EMAILED", recentMessage());
                }
            }
        }

        // Pass 3: render each remaining recipient's own message.
        if (render) {

            for (Eval ev : evals) {

                if (!ev.dto.isEligible()) {
                    continue;
                }

                EmailPlaceholderRenderer.Rendered s = EmailPlaceholderRenderer.render(subject, ev.lead, ev.opportunity);
                EmailPlaceholderRenderer.Rendered b = EmailPlaceholderRenderer.render(body, ev.lead, ev.opportunity);

                List<String> issues = new ArrayList<>(s.issues());
                b.issues().stream().filter(i -> !issues.contains(i)).forEach(issues::add);

                if (!issues.isEmpty()) {
                    ev.exclude("UNRESOLVED_PLACEHOLDER", String.join("; ", issues));
                } else {
                    ev.dto.setRenderedSubject(s.text());
                    ev.dto.setRenderedBody(b.text());
                }
            }
        }

        // Pass 4: one email per distinct address. Not silent: the later records
        // are excluded with a visible reason, the first is annotated.
        Map<String, Eval> firstByEmail = new HashMap<>();

        for (Eval ev : evals) {

            if (!ev.dto.isEligible()) {
                continue;
            }

            Eval first = firstByEmail.putIfAbsent(ev.email, ev);

            if (first != null) {

                ev.exclude("DUPLICATE_EMAIL",
                        "Same email address as " + display(first) + " (already included) - one email is sent per address.");

                String note = "Other selected records share this address - only this one will be emailed.";

                if (!first.dto.getWarnings().contains(note)) {
                    first.dto.getWarnings().add(note);
                }
            }
        }

        return evals;
    }

    private boolean mayEmail(Employee employee, BulkEmailTarget target, Lead lead) {

        Long owner = lead.getAssignedEmployee() != null ? lead.getAssignedEmployee().getId() : null;

        try {

            accessControlService.assertCanAccess(employee, EMAIL_SEND, owner);

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

    private String hash(String subject, String body) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(subject.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private BulkEmailBatchResponse info(EmailBulkBatch batch, boolean withContent) {
        return new BulkEmailBatchResponse(
                batch.getBatchId(), batch.getTarget(), batch.getTemplateId(), batch.getRecipientCount(),
                maxChunk, maxRecipients, batch.getCreatedAt(),
                withContent ? batch.getSubject() : null,
                withContent ? batch.getBody() : null,
                withContent ? batch.recipientIdList() : null);
    }

    private BulkEmailSendResponse.Result result(Eval ev, String outcome, String detail, boolean already) {
        return new BulkEmailSendResponse.Result(
                ev.id, ev.dto.getCompanyName(), ev.dto.getEmail(), outcome, detail, already, null);
    }

    private BulkEmailSendResponse.Result excluded(Eval ev, String code, String message) {
        return new BulkEmailSendResponse.Result(
                ev.id, ev.dto.getCompanyName(), ev.dto.getEmail(), "EXCLUDED", message, false, code);
    }

    private BulkEmailSendResponse.Result fromRow(EmailSendLog row, String outcome, boolean already) {
        return new BulkEmailSendResponse.Result(
                row.getTargetId(), row.getRecordLabel(), row.getRecipientEmail(), outcome, row.getDetail(), already, null);
    }

    private BulkEmailSendResponse summarize(
            String batchId, List<BulkEmailSendResponse.Result> results, BulkEmailBatchResponse batch) {

        int accepted = 0, failed = 0, unknown = 0, inProgress = 0, excluded = 0, notAttempted = 0;

        for (BulkEmailSendResponse.Result r : results) {
            switch (r.getOutcome()) {
                case "ACCEPTED" -> accepted++;
                case "FAILED" -> failed++;
                case "UNKNOWN" -> unknown++;
                case "IN_PROGRESS", "PENDING" -> inProgress++;
                case "NOT_ATTEMPTED" -> notAttempted++;
                default -> excluded++;
            }
        }

        return new BulkEmailSendResponse(
                batchId, accepted, failed, unknown, inProgress, excluded, notAttempted, results, batch);
    }

    private String label(Eval ev) {
        String label = ev.opportunity != null && ev.opportunity.getTitle() != null
                ? ev.lead.getCompanyName() + " - " + ev.opportunity.getTitle()
                : ev.lead.getCompanyName();
        return label != null && label.length() > 255 ? label.substring(0, 255) : label;
    }

    private String display(Eval ev) {
        return ev.dto.getCompanyName() != null ? ev.dto.getCompanyName() : "record #" + ev.id;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
