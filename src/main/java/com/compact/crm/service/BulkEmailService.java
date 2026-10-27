package com.compact.crm.service;

import com.compact.crm.dto.request.BulkEmailRequest;
import com.compact.crm.dto.response.BulkEmailPreviewResponse;
import com.compact.crm.dto.response.BulkEmailSendResponse;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.compact.crm.security.AccessControlService.EMAIL_SEND;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;

// Bulk email for Leads and Opportunities: one INDIVIDUAL email per recipient
// (never a shared To/CC), through the existing BrevoEmailClient and activity
// log, with the same EMAIL_SEND scope the single-recipient flow uses.
//
// Sending model (chosen after auditing the platform):
//  - The browser sends the batch in CHUNKS of at most `max-chunk` (10) records,
//    one request each, all sharing one client-generated batchId. A synchronous
//    request for a whole batch would run into the 30 s browser timeout and the
//    free-tier instance's slow CPU, and an ambiguous timeout is precisely what
//    must not happen mid-batch. No queue/worker infrastructure exists, and a
//    modest batch does not warrant adding any.
//  - /preview validates and authorizes the WHOLE selection up front. Every
//    chunk is then validated and authorized AGAIN immediately before sending.
//  - Each recipient is claimed in email_send_log (unique per batch+record)
//    before the provider is called, so a double click, a repeated request or a
//    retried chunk can never send the same recipient twice.
//  - Outcomes: ACCEPTED (provider 2xx - not proof of delivery), FAILED (a 4xx:
//    definitely not sent, retried only when the user retries the batch) and
//    UNKNOWN (timeout / 5xx / I-O error: may have been sent - NEVER resent
//    automatically). A claim abandoned mid-send (server restart) is reported
//    UNKNOWN too.
//
// Not @Transactional on purpose: provider calls must not run inside an open DB
// transaction, and each recipient's outcome is independent.
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkEmailService {

    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    // Deliberately simple and strict: one address, no spaces/commas/semicolons.
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+'-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    // A PENDING claim older than this means the process died (or the final DB
    // write failed) after we may have called the provider: outcome UNKNOWN.
    private static final Duration PENDING_STALE_AFTER = Duration.ofMinutes(2);

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final EmailSendLogRepository sendLogRepository;
    private final EmailTemplateService emailTemplateService;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;
    private final ActivityLogService activityLogService;
    private final BrevoEmailClient brevoEmailClient;

    @Value("${crm.mail.bulk.max-recipients:100}")
    private int maxRecipients = 100;

    @Value("${crm.mail.bulk.max-chunk:10}")
    private int maxChunk = 10;

    // ------------------------------------------------------------------ preview

    public BulkEmailPreviewResponse preview(BulkEmailRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SEND);

        List<Long> ids = distinctIds(request.getIds(), maxRecipients);

        List<Eval> evals = evaluate(employee, request.getTarget(), ids, request.getSubject(), request.getBody());

        List<BulkEmailPreviewResponse.Recipient> recipients = evals.stream().map(e -> e.dto).toList();
        long eligible = recipients.stream().filter(BulkEmailPreviewResponse.Recipient::isEligible).count();

        return new BulkEmailPreviewResponse(
                request.getTarget(), ids.size(), (int) eligible, (int) (recipients.size() - eligible), recipients);
    }

    // --------------------------------------------------------------------- send

    public BulkEmailSendResponse send(BulkEmailRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SEND);

        String batchId = request.getBatchId();

        if (batchId == null || !BATCH_ID.matcher(batchId).matches()) {
            throw new IllegalArgumentException("A valid batch id is required.");
        }

        if (isBlank(request.getSubject()) || isBlank(request.getBody())) {
            throw new IllegalArgumentException("Subject and message are required.");
        }

        List<Long> ids = distinctIds(request.getIds(), maxChunk);

        if (request.getTemplateId() != null) {

            EmailTemplate template = emailTemplateService.getById(request.getTemplateId());

            if (!Boolean.TRUE.equals(template.getIsActive())) {
                throw new IllegalArgumentException("That email template is no longer active.");
            }
        }

        if (sendLogRepository.existsByBatchIdAndSentByEmployeeIdNot(batchId, employee.getId())) {
            throw new AccessDeniedException("This batch belongs to another user.");
        }

        // Revalidated NOW, not trusted from the earlier preview.
        List<Eval> evals = evaluate(employee, request.getTarget(), ids, request.getSubject(), request.getBody());

        List<BulkEmailSendResponse.Result> results = new ArrayList<>();

        for (Eval ev : evals) {

            if (!ev.dto.isEligible()) {
                results.add(result(ev, "EXCLUDED", ev.dto.getExclusionMessage(), false));
                continue;
            }

            if (duplicateInBatch(batchId, request.getTarget(), ev)) {
                results.add(result(ev, "EXCLUDED",
                        "Another record in this batch already has this email address - one email per address.", false));
                continue;
            }

            results.add(deliver(employee, batchId, request, ev));
        }

        return summarize(batchId, results);
    }

    // ------------------------------------------------------------ batch results

    // Reload-safe view of what has happened to a batch so far. Only the user
    // who sent it (or an admin) can read it.
    public BulkEmailSendResponse results(String batchId) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SEND);

        if (batchId == null || !BATCH_ID.matcher(batchId).matches()) {
            throw new IllegalArgumentException("A valid batch id is required.");
        }

        List<EmailSendLog> rows = sendLogRepository.findByBatchIdOrderByIdAsc(batchId);

        if (!currentUserService.isAdmin()
                && rows.stream().anyMatch(r -> !employee.getId().equals(r.getSentByEmployeeId()))) {
            throw new AccessDeniedException("This batch belongs to another user.");
        }

        List<BulkEmailSendResponse.Result> results = new ArrayList<>();

        for (EmailSendLog row : rows) {
            results.add(new BulkEmailSendResponse.Result(
                    row.getTargetId(), row.getRecordLabel(), row.getRecipientEmail(),
                    effective(row).name(), row.getDetail(), true));
        }

        return summarize(batchId, results);
    }

    // ----------------------------------------------------------------- internals

    private BulkEmailSendResponse.Result deliver(
            Employee employee, String batchId, BulkEmailRequest request, Eval ev) {

        String email = ev.email;

        Optional<EmailSendLog> existing = sendLogRepository.findByBatchIdAndTargetAndTargetId(
                batchId, request.getTarget(), ev.id);

        EmailSendLog row;

        if (existing.isPresent()) {

            row = existing.get();

            switch (effective(row)) {

                case ACCEPTED:
                    return prior(ev, row, "ACCEPTED");

                case UNKNOWN:
                    // Persist the stale-PENDING -> UNKNOWN conclusion, then report it.
                    sendLogRepository.transition(row.getId(), EmailSendStatus.PENDING, EmailSendStatus.UNKNOWN,
                            "Sending was interrupted - outcome unknown", LocalDateTime.now());
                    return prior(ev, row, "UNKNOWN");

                case PENDING:
                    return prior(ev, row, "IN_PROGRESS");

                case FAILED:
                    // Confirmed not sent, so a retry is safe - but only one request may win it.
                    if (sendLogRepository.transition(row.getId(), EmailSendStatus.FAILED, EmailSendStatus.PENDING,
                            null, LocalDateTime.now()) != 1) {
                        return prior(ev, row, "IN_PROGRESS");
                    }
                    row = sendLogRepository.findById(row.getId()).orElse(row);
                    row.setRecipientEmail(email);
                    row.setRecordLabel(label(ev));
                    break;

                default:
                    return prior(ev, row, "UNKNOWN");
            }

        } else {

            row = EmailSendLog.builder()
                    .batchId(batchId)
                    .target(request.getTarget())
                    .targetId(ev.id)
                    .recordLabel(label(ev))
                    .recipientEmail(email)
                    .status(EmailSendStatus.PENDING)
                    .templateId(request.getTemplateId())
                    .sentByEmployeeId(employee.getId())
                    .build();

            try {
                row = sendLogRepository.saveAndFlush(row);
            } catch (DataIntegrityViolationException e) {
                // A concurrent request claimed this recipient first.
                return result(ev, "IN_PROGRESS", "Already being sent by another request.", true);
            }
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
            logActivity(employee, request.getTarget(), ev, email);
        }

        return result(ev, status.name(), outcome.detail(), false);
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
    private boolean duplicateInBatch(String batchId, BulkEmailTarget target, Eval ev) {

        return sendLogRepository.findByBatchIdAndRecipientEmailAndStatusIn(
                        batchId, ev.email,
                        List.of(EmailSendStatus.ACCEPTED, EmailSendStatus.UNKNOWN, EmailSendStatus.PENDING))
                .stream()
                .anyMatch(l -> !(l.getTarget() == target && l.getTargetId().equals(ev.id)));
    }

    private EmailSendStatus effective(EmailSendLog row) {

        if (row.getStatus() == EmailSendStatus.PENDING
                && row.getUpdatedAt() != null
                && row.getUpdatedAt().isBefore(LocalDateTime.now().minus(PENDING_STALE_AFTER))) {
            return EmailSendStatus.UNKNOWN;
        }

        return row.getStatus();
    }

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
    private List<Eval> evaluate(Employee employee, BulkEmailTarget target, List<Long> ids,
                                String subject, String body) {

        boolean render = !isBlank(subject) || !isBlank(body);

        List<Eval> evals = new ArrayList<>();

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
            ev.email = raw.toLowerCase(Locale.ROOT);

            if (raw.isEmpty()) {
                ev.exclude("NO_EMAIL", "No email address on file.");
            } else if (raw.length() > 254 || !EMAIL.matcher(raw).matches()) {
                ev.exclude("INVALID_EMAIL", "The email address on file is not valid.");
            }

            if (lead.getLeadStatus() == LeadStatus.INVALID) {
                ev.dto.getWarnings().add("The lead is marked INVALID.");
            }

            if (ev.dto.isEligible() && render) {

                EmailPlaceholderRenderer.Rendered s = EmailPlaceholderRenderer.render(subject, lead, opportunity);
                EmailPlaceholderRenderer.Rendered b = EmailPlaceholderRenderer.render(body, lead, opportunity);

                List<String> issues = new ArrayList<>(s.issues());
                b.issues().stream().filter(i -> !issues.contains(i)).forEach(issues::add);

                if (!issues.isEmpty()) {
                    ev.exclude("UNRESOLVED_PLACEHOLDER", String.join("; ", issues));
                } else {
                    ev.dto.setRenderedSubject(s.text());
                    ev.dto.setRenderedBody(b.text());
                }
            }

            evals.add(ev);
        }

        // One email per distinct address per batch. Not silent: the later
        // records are excluded with a visible reason, the first is annotated.
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

    private BulkEmailSendResponse.Result result(Eval ev, String outcome, String detail, boolean already) {
        return new BulkEmailSendResponse.Result(
                ev.id, ev.dto.getCompanyName(), ev.dto.getEmail(), outcome, detail, already);
    }

    private BulkEmailSendResponse.Result prior(Eval ev, EmailSendLog row, String outcome) {
        return new BulkEmailSendResponse.Result(
                ev.id, ev.dto.getCompanyName(), row.getRecipientEmail(), outcome, row.getDetail(), true);
    }

    private BulkEmailSendResponse summarize(String batchId, List<BulkEmailSendResponse.Result> results) {

        int accepted = 0, failed = 0, unknown = 0, inProgress = 0, excluded = 0;

        for (BulkEmailSendResponse.Result r : results) {
            switch (r.getOutcome()) {
                case "ACCEPTED" -> accepted++;
                case "FAILED" -> failed++;
                case "UNKNOWN" -> unknown++;
                case "IN_PROGRESS", "PENDING" -> inProgress++;
                default -> excluded++;
            }
        }

        return new BulkEmailSendResponse(batchId, accepted, failed, unknown, inProgress, excluded, results);
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
