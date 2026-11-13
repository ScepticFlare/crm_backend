package com.compact.crm.service;

import com.compact.crm.dto.request.WhatsAppBulkConsentRequest;
import com.compact.crm.dto.request.WhatsAppConsentRequest;
import com.compact.crm.dto.response.PageResponse;
import com.compact.crm.dto.response.WhatsAppBulkConsentResult;
import com.compact.crm.dto.response.WhatsAppConsentAuditResponse;
import com.compact.crm.dto.response.WhatsAppConsentResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.WhatsAppConsent;
import com.compact.crm.entity.WhatsAppConsentAudit;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.WhatsAppConsentStatus;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.WhatsAppConsentAuditRepository;
import com.compact.crm.repository.WhatsAppConsentRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.WHATSAPP_CONSENT_MANAGE;

// WhatsApp consent, keyed by NORMALIZED number (WhatsAppNumbers). ONE service
// answers "may this number be messaged in bulk?" for preview and send, so no
// path can bypass it.
//
// Rules:
//  * No row = no consent. Nothing is ever opted in automatically, and having a
//    phone number on a lead is not consent.
//  * An opt-in needs a source, the date it was given (not in the future) and
//    evidence. It can be recorded by whoever may edit the lead (LEAD_MANAGE in
//    scope) or by an administrator (WHATSAPP_CONSENT_MANAGE).
//  * An opt-out can be recorded by the same people, and also arrives from an
//    inbound STOP / UNSUBSCRIBE (WhatsAppWebhookService). It takes effect
//    immediately for every record sharing the number.
//  * Once a number has opted out, ONLY an administrator can record a new
//    opt-in, and only with a consent date later than the opt-out.
//  * The admin bulk import only adds opt-ins with stated source / date /
//    evidence; it never changes a number that opted out.
//  * Every change writes an append-only whatsapp_consent_audit row.
//
// This is a technical safeguard. Whether a consent is valid in law and under
// WhatsApp's policies remains the responsibility of the people recording it.
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppConsentService {

    public static final String SOURCE_INBOUND_STOP = "INBOUND_STOP";
    public static final String SOURCE_CRM_USER = "CRM_USER";

    private static final LocalDate EARLIEST_CONSENT_DATE = LocalDate.of(2000, 1, 1);

    private final WhatsAppConsentRepository consentRepository;
    private final WhatsAppConsentAuditRepository auditRepository;
    private final LeadRepository leadRepository;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;
    private final ActivityLogService activityLogService;

    @Value("${crm.whatsapp.default-country-code:91}")
    private String countryCode = "91";

    // ------------------------------------------------------------ enforcement

    // Current status for each number (absent = no consent). One query.
    public Map<String, WhatsAppConsentStatus> statuses(Collection<String> phones) {

        Map<String, WhatsAppConsentStatus> out = new HashMap<>();

        if (phones == null || phones.isEmpty()) {
            return out;
        }

        for (WhatsAppConsent c : consentRepository.findByPhoneE164In(new HashSet<>(phones))) {
            out.put(c.getPhoneE164(), c.getStatus());
        }

        return out;
    }

    // Read fresh, immediately before a provider call.
    public Optional<WhatsAppConsentStatus> status(String phone) {
        return consentRepository.findByPhoneE164(phone).map(WhatsAppConsent::getStatus);
    }

    public String normalize(String raw) {
        return WhatsAppNumbers.normalize(raw, countryCode).orElse(null);
    }

    public String mask(String normalized) {
        return WhatsAppNumbers.mask(normalized, countryCode);
    }

    // ------------------------------------------------------------ lead screen

    public WhatsAppConsentResponse getForLead(Long leadId) {

        Employee employee = currentUserService.getCurrentEmployee();
        Lead lead = loadLead(leadId);

        assertLeadAccess(employee, lead, LEAD_VIEW);

        String phone = normalize(lead.getPhone());
        boolean admin = isConsentAdmin(employee);
        boolean mayManage = admin || canAccess(employee, lead, LEAD_MANAGE);

        if (phone == null) {
            return new WhatsAppConsentResponse(null, null, false, null, null, null, null, null, false, false);
        }

        WhatsAppConsent c = consentRepository.findByPhoneE164(phone).orElse(null);
        WhatsAppConsentStatus status = c != null ? c.getStatus() : null;

        boolean canOptIn = mayManage && (status != WhatsAppConsentStatus.OPTED_OUT || admin);

        return new WhatsAppConsentResponse(
                admin ? phone : null, mask(phone), true, status,
                c != null ? c.getSource() : null,
                c != null ? c.getEvidence() : null,
                c != null ? c.getConsentedAt() : null,
                c != null ? c.getUpdatedAt() : null,
                canOptIn, mayManage);
    }

    @Transactional
    public WhatsAppConsentResponse recordForLead(Long leadId, WhatsAppConsentRequest request) {

        Employee employee = currentUserService.getCurrentEmployee();
        Lead lead = loadLead(leadId);

        boolean admin = isConsentAdmin(employee);

        if (!admin) {
            assertLeadAccess(employee, lead, LEAD_MANAGE);
        }

        String phone = normalize(lead.getPhone());

        if (phone == null) {
            throw new IllegalArgumentException(
                    "This lead's phone number is not a valid WhatsApp mobile number, so no consent can be recorded for it.");
        }

        apply(phone, request, employee, admin, lead.getId(), null, null);

        return getForLead(leadId);
    }

    // ------------------------------------------------------------ admin

    @Transactional
    public WhatsAppConsentResponse recordForPhone(WhatsAppConsentRequest request) {

        Employee employee = requireAdmin();

        String phone = normalize(request.getPhone());

        if (phone == null) {
            throw new IllegalArgumentException("That is not a valid WhatsApp mobile number.");
        }

        WhatsAppConsent saved = apply(phone, request, employee, true, null, null, null);

        return toAdminResponse(saved);
    }

    @Transactional
    public WhatsAppBulkConsentResult bulkOptIn(WhatsAppBulkConsentRequest request) {

        Employee employee = requireAdmin();

        LocalDateTime consentedAt = validConsentDate(request.getConsentedAt());
        String source = cleanSource(request.getSource());
        String evidence = request.getEvidence().trim();
        String reference = UUID.randomUUID().toString();

        List<WhatsAppBulkConsentResult.Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int optedIn = 0, already = 0, skipped = 0, invalid = 0, duplicates = 0;

        // Look up every valid number once.
        List<String> normalized = request.getPhones().stream().map(this::normalize).toList();
        Map<String, WhatsAppConsentStatus> current = statuses(normalized.stream().filter(p -> p != null).toList());

        for (int i = 0; i < request.getPhones().size(); i++) {

            String input = request.getPhones().get(i);
            String phone = normalized.get(i);
            String shownInput = input == null ? "" : WhatsAppNumbers.mask(input.replaceAll("[^0-9]", ""), null);

            if (phone == null) {
                invalid++;
                rows.add(new WhatsAppBulkConsentResult.Row(shownInput, null, "INVALID_PHONE"));
                continue;
            }

            if (!seen.add(phone)) {
                duplicates++;
                rows.add(new WhatsAppBulkConsentResult.Row(shownInput, mask(phone), "DUPLICATE_IN_LIST"));
                continue;
            }

            WhatsAppConsentStatus status = current.get(phone);

            if (status == WhatsAppConsentStatus.OPTED_OUT) {
                skipped++;
                rows.add(new WhatsAppBulkConsentResult.Row(shownInput, mask(phone), "SKIPPED_OPTED_OUT"));
                continue;
            }

            if (status == WhatsAppConsentStatus.OPTED_IN) {
                already++;
                rows.add(new WhatsAppBulkConsentResult.Row(shownInput, mask(phone), "ALREADY_OPTED_IN"));
                continue;
            }

            WhatsAppConsent consent = consentRepository.save(WhatsAppConsent.builder()
                    .phoneE164(phone)
                    .status(WhatsAppConsentStatus.OPTED_IN)
                    .source(source)
                    .evidence(evidence)
                    .consentedAt(consentedAt)
                    .recordedByEmployeeId(employee.getId())
                    .build());

            audit(consent, null, employee, reference);
            optedIn++;
            rows.add(new WhatsAppBulkConsentResult.Row(shownInput, mask(phone), "OPTED_IN"));
        }

        log.info("WhatsApp consent bulk import {}: {} opted in, {} already, {} skipped (opted out), {} invalid, {} duplicates",
                reference, optedIn, already, skipped, invalid, duplicates);

        return new WhatsAppBulkConsentResult(reference, optedIn, already, skipped, invalid, duplicates, rows);
    }

    public PageResponse<WhatsAppConsentResponse> list(String query, int page, int size) {

        requireAdmin();

        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        String digits = query == null ? "" : query.replaceAll("[^0-9]", "");

        Page<WhatsAppConsent> result = digits.isEmpty()
                ? consentRepository.findAllByOrderByUpdatedAtDesc(pageable)
                : consentRepository.findByPhoneE164ContainingOrderByUpdatedAtDesc(digits, pageable);

        return new PageResponse<>(result.getContent().stream().map(this::toAdminResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public PageResponse<WhatsAppConsentAuditResponse> audit(int page, int size) {

        requireAdmin();

        Page<WhatsAppConsentAudit> result = auditRepository.findAllByOrderByPerformedAtDescIdDesc(
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));

        return new PageResponse<>(result.getContent().stream().map(a -> new WhatsAppConsentAuditResponse(
                a.getId(), a.getPhoneE164(), a.getPreviousStatus(), a.getNewStatus(), a.getSource(), a.getEvidence(),
                a.getConsentedAt(), a.getLeadId(), a.getImportReference(), a.getPerformedByName(), a.getPerformedAt()))
                .toList(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    // ------------------------------------------------------------ inbound

    // An inbound STOP / UNSUBSCRIBE. Idempotent: an already opted-out number is
    // left as it is. Returns true when the state changed.
    @Transactional
    public boolean optOutFromInbound(String phone, String evidence) {

        WhatsAppConsent existing = consentRepository.findByPhoneE164(phone).orElse(null);

        if (existing != null && existing.getStatus() == WhatsAppConsentStatus.OPTED_OUT) {
            return false;
        }

        WhatsAppConsentStatus previous = existing != null ? existing.getStatus() : null;
        WhatsAppConsent consent = existing != null ? existing : WhatsAppConsent.builder().phoneE164(phone).build();

        consent.setStatus(WhatsAppConsentStatus.OPTED_OUT);
        consent.setSource(SOURCE_INBOUND_STOP);
        consent.setEvidence(evidence);
        consent.setConsentedAt(LocalDateTime.now());
        consent.setRecordedByEmployeeId(null);

        WhatsAppConsent saved = consentRepository.saveAndFlush(consent);

        WhatsAppConsentAudit row = auditRow(saved, previous, null, null);
        row.setPerformedByName("WhatsApp reply");
        auditRepository.save(row);

        return true;
    }

    // ------------------------------------------------------------ internals

    // The one place a consent state is written from a CRM user's request.
    private WhatsAppConsent apply(String phone, WhatsAppConsentRequest request, Employee employee, boolean admin,
                                  Long leadId, Long opportunityId, String importReference) {

        WhatsAppConsent existing = consentRepository.findByPhoneE164(phone).orElse(null);
        WhatsAppConsentStatus previous = existing != null ? existing.getStatus() : null;

        String source;
        String evidence = trimToNull(request.getEvidence());
        LocalDateTime when;

        if (request.getStatus() == WhatsAppConsentStatus.OPTED_IN) {

            if (request.getSource() == null || request.getSource().isBlank()) {
                throw new IllegalArgumentException("Say where the consent was given (source).");
            }

            if (request.getConsentedAt() == null) {
                throw new IllegalArgumentException("Enter the date the consent was given.");
            }

            if (evidence == null) {
                throw new IllegalArgumentException("Describe the evidence for this consent (form, call note, document...).");
            }

            source = cleanSource(request.getSource());
            when = validConsentDate(request.getConsentedAt());

            if (previous == WhatsAppConsentStatus.OPTED_OUT) {

                if (!admin) {
                    throw new AccessDeniedException(
                            "This number has opted out. Only an administrator can record a new opt-in for it.");
                }

                if (!when.toLocalDate().isAfter(existing.getConsentedAt().toLocalDate())) {
                    throw new IllegalArgumentException(
                            "This number opted out on " + existing.getConsentedAt().toLocalDate()
                                    + ". A new opt-in must be dated after that.");
                }
            }

        } else {

            source = request.getSource() == null || request.getSource().isBlank()
                    ? SOURCE_CRM_USER : cleanSource(request.getSource());
            when = request.getConsentedAt() == null ? LocalDateTime.now() : validConsentDate(request.getConsentedAt());
        }

        WhatsAppConsent consent = existing != null ? existing : WhatsAppConsent.builder().phoneE164(phone).build();

        consent.setStatus(request.getStatus());
        consent.setSource(source);
        consent.setEvidence(evidence);
        consent.setConsentedAt(when);
        consent.setLeadId(leadId != null ? leadId : consent.getLeadId());
        consent.setOpportunityId(opportunityId != null ? opportunityId : consent.getOpportunityId());
        consent.setRecordedByEmployeeId(employee.getId());

        WhatsAppConsent saved = consentRepository.saveAndFlush(consent);

        audit(saved, previous, employee, importReference);

        if (leadId != null) {
            activityLogService.log(employee, ActivityModule.LEAD, ActivityAction.WHATSAPP_CONSENT_CHANGED,
                    leadId, null, "WhatsApp " + (request.getStatus() == WhatsAppConsentStatus.OPTED_IN
                            ? "opt-in" : "opt-out") + " recorded (source: " + source + ")");
        }

        return saved;
    }

    private void audit(WhatsAppConsent consent, WhatsAppConsentStatus previous, Employee employee, String importReference) {
        auditRepository.save(auditRow(consent, previous, employee, importReference));
    }

    private WhatsAppConsentAudit auditRow(WhatsAppConsent consent, WhatsAppConsentStatus previous,
                                          Employee employee, String importReference) {
        return WhatsAppConsentAudit.builder()
                .phoneE164(consent.getPhoneE164())
                .previousStatus(previous)
                .newStatus(consent.getStatus())
                .source(consent.getSource())
                .evidence(consent.getEvidence())
                .consentedAt(consent.getConsentedAt())
                .leadId(consent.getLeadId())
                .opportunityId(consent.getOpportunityId())
                .importReference(importReference)
                .performedByEmployeeId(employee != null ? employee.getId() : null)
                .performedByName(employee != null ? employee.getName() : null)
                .performedAt(LocalDateTime.now())
                .build();
    }

    private WhatsAppConsentResponse toAdminResponse(WhatsAppConsent c) {
        return new WhatsAppConsentResponse(c.getPhoneE164(), mask(c.getPhoneE164()), true, c.getStatus(),
                c.getSource(), c.getEvidence(), c.getConsentedAt(), c.getUpdatedAt(), true, true);
    }

    private LocalDateTime validConsentDate(LocalDate date) {

        if (date == null) {
            throw new IllegalArgumentException("Enter the date the consent was given.");
        }

        // "Today" in the business zone (IST), not the server's UTC clock: the
        // browser offers the Indian calendar day, which runs 5h30 ahead of UTC.
        if (date.isAfter(BusinessClock.today())) {
            throw new IllegalArgumentException("The consent date cannot be in the future.");
        }

        if (date.isBefore(EARLIEST_CONSENT_DATE)) {
            throw new IllegalArgumentException("The consent date is not plausible.");
        }

        return date.atStartOfDay();
    }

    // Upper-case, spaces to underscores, only A-Z 0-9 _ - kept.
    private static String cleanSource(String source) {

        String cleaned = source.trim().toUpperCase().replaceAll("\\s+", "_").replaceAll("[^A-Z0-9_\\-]", "");

        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Source must contain letters or digits.");
        }

        return cleaned.length() > 50 ? cleaned.substring(0, 50) : cleaned;
    }

    private Lead loadLead(Long leadId) {
        return leadRepository.findById(leadId).orElseThrow(() -> new ResourceNotFoundException("Lead not found"));
    }

    private boolean canAccess(Employee employee, Lead lead, String permission) {
        try {
            assertLeadAccess(employee, lead, permission);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    private void assertLeadAccess(Employee employee, Lead lead, String permission) {
        Long owner = lead.getAssignedEmployee() != null ? lead.getAssignedEmployee().getId() : null;
        accessControlService.assertCanAccess(employee, permission, owner);
    }

    private boolean isConsentAdmin(Employee employee) {
        return accessControlService.hasPermission(employee, WHATSAPP_CONSENT_MANAGE);
    }

    private Employee requireAdmin() {
        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, WHATSAPP_CONSENT_MANAGE);
        return employee;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
