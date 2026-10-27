package com.compact.crm.service;

import com.compact.crm.dto.request.EmailSuppressionRequest;
import com.compact.crm.dto.response.EmailSuppressionAuditResponse;
import com.compact.crm.dto.response.EmailSuppressionPage;
import com.compact.crm.dto.response.EmailSuppressionResponse;
import com.compact.crm.entity.EmailSuppression;
import com.compact.crm.entity.EmailSuppressionAudit;
import com.compact.crm.entity.Employee;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.EmailSuppressionAuditRepository;
import com.compact.crm.repository.EmailSuppressionRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static com.compact.crm.security.AccessControlService.EMAIL_SUPPRESSION_MANAGE;

// The do-not-email list. ONE service answers "may this address be emailed?" for
// every customer-facing send path (single send, Keep-in-Touch, bulk preview and
// bulk send), so no path can bypass it. A suppression applies to the ADDRESS:
// every lead/opportunity sharing it is blocked.
//
// Managing the list (list / add / remove / audit) needs EMAIL_SUPPRESSION_MANAGE,
// which only ADMIN holds (V13). Every add and removal is written to
// email_suppression_audit; a removal also requires a written justification.
//
// This list is a technical safeguard only. It does not by itself make any
// mailing compliant: consent, opt-out handling and company policy still have to
// be followed by the people sending.
@Service
@RequiredArgsConstructor
public class EmailSuppressionService {

    public static final String SOURCE_ADMIN = "ADMIN_CONSOLE";

    private static final int MAX_PAGE_SIZE = 100;

    private final EmailSuppressionRepository suppressionRepository;
    private final EmailSuppressionAuditRepository auditRepository;
    private final EmployeeRepository employeeRepository;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;

    // ------------------------------------------------------------ enforcement

    // Callable from any authenticated flow; reveals nothing but a yes/no.
    @Transactional(readOnly = true)
    public boolean isSuppressed(String email) {

        String normalized = EmailAddresses.normalize(email);

        return normalized != null && suppressionRepository.findByEmailNormalized(normalized).isPresent();
    }

    // One query for a whole recipient list. Input may be raw or normalized.
    @Transactional(readOnly = true)
    public Set<String> findSuppressed(Collection<String> emails) {

        Set<String> normalized = emails.stream()
                .map(EmailAddresses::normalize)
                .filter(e -> e != null)
                .collect(Collectors.toCollection(HashSet::new));

        if (normalized.isEmpty()) {
            return Set.of();
        }

        return suppressionRepository.findByEmailNormalizedIn(normalized).stream()
                .map(EmailSuppression::getEmailNormalized)
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------ admin workflow

    @Transactional(readOnly = true)
    public EmailSuppressionPage<EmailSuppressionResponse> list(String query, int page, int size) {

        requireManage();

        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), clamp(size), Sort.by(Sort.Direction.DESC, "id"));

        String fragment = EmailAddresses.normalize(query);

        Page<EmailSuppression> result = fragment == null
                ? suppressionRepository.findAll(pageable)
                : suppressionRepository.findByEmailNormalizedContaining(fragment, pageable);

        return new EmailSuppressionPage<>(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @Transactional
    public EmailSuppressionResponse add(EmailSuppressionRequest request) {

        Employee actor = requireManage();

        String email = EmailAddresses.normalize(request.getEmail());

        if (email == null || !EmailAddresses.isValid(email)) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }

        if (suppressionRepository.findByEmailNormalized(email).isPresent()) {
            throw new IllegalArgumentException("That email address is already on the do-not-email list.");
        }

        EmailSuppression saved;

        try {
            saved = suppressionRepository.saveAndFlush(EmailSuppression.builder()
                    .emailNormalized(email)
                    .reason(request.getReason())
                    .source(SOURCE_ADMIN)
                    .note(blankToNull(request.getNote()))
                    .createdByEmployeeId(actor.getId())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Two admins added the same address at once; the unique key decided.
            throw new IllegalArgumentException("That email address is already on the do-not-email list.");
        }

        auditRepository.save(EmailSuppressionAudit.builder()
                .emailNormalized(email)
                .action(EmailSuppressionAudit.ADDED)
                .reason(request.getReason())
                .note(blankToNull(request.getNote()))
                .performedByEmployeeId(actor.getId())
                .build());

        return toResponse(saved);
    }

    // A removal re-enables emailing to that address, so it needs a reason and is audited.
    @Transactional
    public void remove(Long id, String justification) {

        Employee actor = requireManage();

        if (justification == null || justification.trim().length() < 5) {
            throw new IllegalArgumentException(
                    "Explain why this address may be emailed again (at least 5 characters).");
        }

        if (justification.trim().length() > 500) {
            throw new IllegalArgumentException("The explanation is too long (500 characters maximum).");
        }

        EmailSuppression existing = suppressionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Suppression entry not found"));

        suppressionRepository.delete(existing);

        auditRepository.save(EmailSuppressionAudit.builder()
                .emailNormalized(existing.getEmailNormalized())
                .action(EmailSuppressionAudit.REMOVED)
                .reason(existing.getReason())
                .note(justification.trim())
                .performedByEmployeeId(actor.getId())
                .build());
    }

    @Transactional(readOnly = true)
    public EmailSuppressionPage<EmailSuppressionAuditResponse> audit(int page, int size) {

        requireManage();

        Page<EmailSuppressionAudit> result = auditRepository.findAllByOrderByIdDesc(
                PageRequest.of(Math.max(page, 0), clamp(size)));

        return new EmailSuppressionPage<>(
                result.getContent().stream().map(this::toAuditResponse).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    // ------------------------------------------------------------ helpers

    private Employee requireManage() {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SUPPRESSION_MANAGE);

        return employee;
    }

    private int clamp(int size) {
        return size <= 0 ? 25 : Math.min(size, MAX_PAGE_SIZE);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nameOf(Long employeeId) {

        if (employeeId == null) {
            return null;
        }

        return employeeRepository.findById(employeeId).map(Employee::getName).orElse(null);
    }

    private EmailSuppressionResponse toResponse(EmailSuppression s) {
        return new EmailSuppressionResponse(s.getId(), s.getEmailNormalized(), s.getReason(), s.getSource(),
                s.getNote(), s.getCreatedByEmployeeId(), nameOf(s.getCreatedByEmployeeId()), s.getCreatedAt());
    }

    private EmailSuppressionAuditResponse toAuditResponse(EmailSuppressionAudit a) {
        return new EmailSuppressionAuditResponse(a.getId(), a.getEmailNormalized(), a.getAction(), a.getReason(),
                a.getNote(), a.getPerformedByEmployeeId(), nameOf(a.getPerformedByEmployeeId()), a.getPerformedAt());
    }
}
