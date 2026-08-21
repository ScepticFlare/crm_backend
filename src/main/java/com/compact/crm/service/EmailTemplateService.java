package com.compact.crm.service;

import com.compact.crm.dto.request.EmailTemplateRequest;
import com.compact.crm.entity.EmailTemplate;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.EmailTemplateType;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.EmailTemplateRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.EMAIL_TEMPLATE_MANAGE;

// CRUD for the reusable, org-wide email template library (Product Brochure
// / Keep in Touch). Listing/reading is open to any authenticated user (same
// convention as Product/Industry master data) - only create/update/
// deactivate require EMAIL_TEMPLATE_MANAGE (ADMIN-only, see V6 migration).
// Placeholder substitution for a specific Lead is NOT done here - that's
// Lead-specific business logic that lives in LeadEmailService, which is the
// only thing that needs it.
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final EmailTemplateRepository emailTemplateRepository;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;

    @Transactional
    public EmailTemplate create(EmailTemplateRequest request) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, EMAIL_TEMPLATE_MANAGE);

        EmailTemplate template = EmailTemplate.builder()
                .name(request.getName().trim())
                .subject(request.getSubject())
                .body(request.getBody())
                .type(request.getType())
                .createdByEmployee(currentEmployee)
                .createdByName(currentEmployee.getName())
                .isActive(true)
                .isDefault(Boolean.TRUE.equals(request.getIsDefault()))
                .build();

        if (template.getIsDefault()) {
            clearExistingDefault(request.getType());
        }

        return emailTemplateRepository.save(template);
    }

    public List<EmailTemplate> listByType(EmailTemplateType type) {

        if (type == null) {
            return emailTemplateRepository.findByIsActiveTrueOrderByNameAsc();
        }

        return emailTemplateRepository.findByTypeAndIsActiveTrueOrderByNameAsc(type);
    }

    public EmailTemplate getById(Long id) {

        return emailTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email template not found"));
    }

    // The composer's initial prefill before the user picks a template - see
    // entity.EmailTemplate.isDefault. Returns empty if no default is
    // currently designated for this type (e.g. it was deactivated) rather
    // than guessing at a fallback; the frontend just opens with a blank
    // composer in that case, same as picking "no template."
    public Optional<EmailTemplate> getDefaultForType(EmailTemplateType type) {
        return emailTemplateRepository.findByTypeAndIsDefaultTrueAndIsActiveTrue(type);
    }

    @Transactional
    public EmailTemplate update(Long id, EmailTemplateRequest request) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, EMAIL_TEMPLATE_MANAGE);

        EmailTemplate template = getById(id);

        template.setName(request.getName().trim());
        template.setSubject(request.getSubject());
        template.setBody(request.getBody());
        template.setType(request.getType());

        boolean shouldBeDefault = Boolean.TRUE.equals(request.getIsDefault());

        if (shouldBeDefault) {
            clearExistingDefault(request.getType());
        }

        template.setIsDefault(shouldBeDefault);

        return emailTemplateRepository.save(template);
    }

    public void deactivate(Long id) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, EMAIL_TEMPLATE_MANAGE);

        EmailTemplate template = getById(id);
        template.setIsActive(false);
        // A deactivated template can no longer be the default prefill.
        template.setIsDefault(false);

        emailTemplateRepository.save(template);
    }

    public void activate(Long id) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, EMAIL_TEMPLATE_MANAGE);

        EmailTemplate template = getById(id);
        template.setIsActive(true);

        emailTemplateRepository.save(template);
    }

    // Enforces "at most one default per type" in application code rather
    // than a DB constraint (see V6 migration comment) - unsets whichever
    // template currently holds the default for this type before the new one
    // is saved as default.
    private void clearExistingDefault(EmailTemplateType type) {

        for (EmailTemplate existing : emailTemplateRepository.findByTypeAndIsDefaultTrue(type)) {
            existing.setIsDefault(false);
            emailTemplateRepository.save(existing);
        }
    }

}
