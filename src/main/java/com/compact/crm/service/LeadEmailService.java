package com.compact.crm.service;

import com.compact.crm.dto.request.BulkKeepInTouchEmailRequest;
import com.compact.crm.dto.request.SendLeadEmailRequest;
import com.compact.crm.dto.response.BulkEmailEligibilityResponse;
import com.compact.crm.dto.response.BulkEmailResult;
import com.compact.crm.dto.response.EmailPreviewResponse;
import com.compact.crm.dto.response.LeadEmailSendResponse;
import com.compact.crm.entity.Document;
import com.compact.crm.entity.EmailTemplate;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.DocumentType;
import com.compact.crm.enums.EmailTemplateType;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.compact.crm.security.AccessControlService.EMAIL_SEND;

// Orchestrates the Lead email feature end to end: authorization + lookup
// (Lead, template, documents), placeholder substitution, actually sending
// through JavaMailSender, and activity logging on success. Kept separate
// from LeadService so that file - already large, already well tested -
// doesn't grow a second, unrelated responsibility; this class depends on
// LeadRepository directly rather than routing through LeadService, the same
// way OpportunityService/CustomerService each own their own repository
// access rather than calling into LeadService for it.
@Service
@RequiredArgsConstructor
public class LeadEmailService {

    private final LeadRepository leadRepository;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;
    private final EmailTemplateService emailTemplateService;
    private final DocumentService documentService;
    private final ActivityLogService activityLogService;
    private final JavaMailSender mailSender;

    @Value("${crm.mail.from-address}")
    private String fromAddress;

    @Value("${crm.mail.from-name}")
    private String fromName;

    // Prefill for the composer: renders the chosen (or default, if
    // templateId is omitted) template's subject/body against this Lead's
    // placeholders, and suggests relevant documents. Read-only, no activity
    // logged - only an actual send is a loggable action (mirrors
    // LeadService.getLeadById logging VIEW but nothing here logging a
    // "previewed" event, since a preview isn't a meaningful business
    // action).
    public EmailPreviewResponse previewIndividual(Long leadId, EmailTemplateType type, Long templateId) {

        Lead lead = getAuthorizedLead(leadId);
        EmailTemplate template = resolveTemplate(type, templateId);

        String subject = template != null ? renderPlaceholders(template.getSubject(), lead) : "";
        String body = template != null ? renderPlaceholders(template.getBody(), lead) : "";

        return new EmailPreviewResponse(
                template != null ? template.getId() : null,
                subject,
                body,
                suggestDocuments(type, lead)
        );
    }

    public LeadEmailSendResponse sendIndividual(Long leadId, SendLeadEmailRequest request) {

        Lead lead = getAuthorizedLead(leadId);
        validateHasEmail(lead);

        List<Document> documents = resolveDocuments(request.getDocumentIds());

        sendMail(lead.getEmail(), request.getSubject(), request.getBody(), documents);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.LEAD, ActivityAction.EMAIL_SENT,
                lead.getId(), lead.getCompanyName(),
                describeEmailType(request.getType()) + " sent to " + lead.getEmail()
        );

        return new LeadEmailSendResponse(true, lead.getId(), lead.getEmail());
    }

    // Read-only eligibility check for the bulk review step - "X selected, Y
    // have valid emails, Z do not" - before anything is actually sent.
    // Ineligibility covers both "no email on file" and "outside your
    // EMAIL_SEND scope" without distinguishing the two to the caller here;
    // the actual send (below) reports per-id reasons for anyone who still
    // ends up skipped.
    public BulkEmailEligibilityResponse bulkEligibility(List<Long> leadIds) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        List<Long> eligible = new ArrayList<>();
        List<Long> ineligible = new ArrayList<>();

        for (Long id : leadIds) {

            Optional<Lead> leadOpt = leadRepository.findById(id);

            if (leadOpt.isEmpty() || !isAuthorizedForEmail(currentEmployee, leadOpt.get())
                    || isBlank(leadOpt.get().getEmail())) {
                ineligible.add(id);
                continue;
            }

            eligible.add(id);
        }

        return new BulkEmailEligibilityResponse(eligible, ineligible, eligible.size(), ineligible.size());
    }

    // Bulk Keep in Touch only (per the business requirement - different
    // Leads can have different interested products, so there is no bulk
    // Product Brochure flow). subject/body may still contain placeholders
    // (a single shared draft can't be pre-substituted per-recipient the way
    // the individual composer's preview is) - each recipient gets its own
    // substitution here. Each Lead is independent: an unauthorized,
    // emailless, or send-failed Lead is skipped with a reason rather than
    // aborting the whole batch, matching LeadService.bulkDeleteLeads.
    // Deliberately not @Transactional - sending N emails synchronously
    // inside one open DB transaction would hold it for the duration of every
    // SMTP round-trip, and each Lead's outcome is independent, not atomic
    // with the others.
    public BulkEmailResult sendBulkKeepInTouch(BulkKeepInTouchEmailRequest request) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, EMAIL_SEND);

        List<Document> documents = resolveDocuments(request.getDocumentIds());

        List<Long> succeeded = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        Map<Long, String> skipReasons = new LinkedHashMap<>();

        for (Long id : request.getLeadIds()) {

            Optional<Lead> leadOpt = leadRepository.findById(id);

            if (leadOpt.isEmpty()) {
                skipped.add(id);
                skipReasons.put(id, "Lead not found");
                continue;
            }

            Lead lead = leadOpt.get();

            if (!isAuthorizedForEmail(currentEmployee, lead)) {
                skipped.add(id);
                skipReasons.put(id, "Not authorized");
                continue;
            }

            if (isBlank(lead.getEmail())) {
                skipped.add(id);
                skipReasons.put(id, "No email address");
                continue;
            }

            try {

                String subject = renderPlaceholders(request.getSubject(), lead);
                String body = renderPlaceholders(request.getBody(), lead);

                sendMail(lead.getEmail(), subject, body, documents);

            } catch (RuntimeException e) {
                skipped.add(id);
                skipReasons.put(id, "Send failed");
                continue;
            }

            succeeded.add(id);

            activityLogService.log(
                    currentEmployee,
                    ActivityModule.LEAD, ActivityAction.EMAIL_SENT,
                    lead.getId(), lead.getCompanyName(),
                    "Keep-in-touch email sent to " + lead.getEmail()
            );
        }

        return new BulkEmailResult(succeeded, skipped, skipReasons);
    }

    private Lead getAuthorizedLead(Long id) {

        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee, EMAIL_SEND, lead.getAssignedEmployee().getId());

        return lead;
    }

    private boolean isAuthorizedForEmail(Employee employee, Lead lead) {

        try {
            accessControlService.assertCanAccess(
                    employee, EMAIL_SEND,
                    lead.getAssignedEmployee() != null ? lead.getAssignedEmployee().getId() : null);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    private void validateHasEmail(Lead lead) {

        if (isBlank(lead.getEmail())) {
            throw new IllegalArgumentException("This lead does not have an email address.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private EmailTemplate resolveTemplate(EmailTemplateType type, Long templateId) {

        if (templateId != null) {
            return emailTemplateService.getById(templateId);
        }

        return emailTemplateService.getDefaultForType(type).orElse(null);
    }

    private List<Document> resolveDocuments(List<Long> documentIds) {

        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();

        for (Long id : documentIds) {
            documents.add(documentService.getById(id));
        }

        return documents;
    }

    // Product Brochure: suggests brochures linked to any product the Lead
    // is actually interested in (Lead.leadProducts - a Lead can have more
    // than one). Keep in Touch: suggests the company profile / general
    // documents, since the brochure isn't relevant there. Purely a
    // suggestion - the frontend attachment picker still lets the user
    // freely add or remove documents.
    private List<Long> suggestDocuments(EmailTemplateType type, Lead lead) {

        if (type == EmailTemplateType.PRODUCT_BROCHURE) {

            Set<Long> productIds = lead.getLeadProducts() == null
                    ? Set.of()
                    : lead.getLeadProducts().stream()
                        .map(LeadProduct::getProduct)
                        .filter(p -> p != null)
                        .map(p -> p.getId())
                        .collect(Collectors.toCollection(LinkedHashSet::new));

            List<Long> suggested = new ArrayList<>();

            for (Long productId : productIds) {
                documentService.list(DocumentType.PRODUCT_BROCHURE, productId)
                        .forEach(doc -> suggested.add(doc.getId()));
            }

            return suggested;
        }

        return documentService.list(DocumentType.COMPANY_PROFILE, null)
                .stream()
                .map(Document::getId)
                .toList();
    }

    private String describeEmailType(EmailTemplateType type) {
        return type == EmailTemplateType.PRODUCT_BROCHURE
                ? "Product brochure email"
                : "Keep-in-touch email";
    }

    // {{contactPerson}} / {{companyName}} / {{interestedProduct}} - a
    // deliberately small, closed set of placeholders (per the business
    // requirement not to over-engineer templating), plain String.replace
    // rather than a templating engine.
    private String renderPlaceholders(String text, Lead lead) {

        if (text == null) {
            return "";
        }

        String interestedProduct = lead.getLeadProducts() == null
                ? ""
                : lead.getLeadProducts().stream()
                    .map(LeadProduct::getProduct)
                    .filter(p -> p != null)
                    .map(p -> p.getName())
                    .distinct()
                    .collect(Collectors.joining(", "));

        return text
                .replace("{{contactPerson}}", nullToEmpty(lead.getContactPerson()))
                .replace("{{companyName}}", nullToEmpty(lead.getCompanyName()))
                .replace("{{interestedProduct}}", interestedProduct);
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private void sendMail(String to, String subject, String body, List<Document> attachments) {

        try {

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, !attachments.isEmpty(), "UTF-8");

            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);

            for (Document document : attachments) {

                byte[] bytes = documentService.loadBytes(document);

                helper.addAttachment(
                        document.getFileName(),
                        new ByteArrayResource(bytes),
                        document.getContentType()
                );
            }

            mailSender.send(message);

        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("Unable to compose the email.", e);
        } catch (MailException e) {
            throw new IllegalStateException("Unable to send the email. Please try again.", e);
        }
    }

}
