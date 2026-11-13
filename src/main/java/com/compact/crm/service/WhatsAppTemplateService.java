package com.compact.crm.service;

import com.compact.crm.dto.request.WhatsAppTemplatePreviewRequest;
import com.compact.crm.dto.request.WhatsAppTemplateRequest;
import com.compact.crm.dto.response.WhatsAppTemplatePreviewResponse;
import com.compact.crm.dto.response.WhatsAppTemplateResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.WhatsAppTemplate;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.WhatsAppTemplateRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static com.compact.crm.security.AccessControlService.WHATSAPP_SEND;
import static com.compact.crm.security.AccessControlService.WHATSAPP_TEMPLATE_MANAGE;

// CRM-managed WhatsApp templates. Authoring (create / edit / activate /
// deactivate / preview) needs WHATSAPP_TEMPLATE_MANAGE (ADMIN only, V15).
// Choosing one for a send needs WHATSAPP_SEND and only ever offers ACTIVE
// templates that validate. A template with problems cannot be saved, so an
// active template is always usable; the check is repeated when a batch is made.
//
// Editing the body increments `version`. Existing bulk batches keep the body
// snapshot and version they were created with, so an edit never changes what
// an already-created batch sends.
@Service
@RequiredArgsConstructor
public class WhatsAppTemplateService {

    private final WhatsAppTemplateRepository templateRepository;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;

    // ---------------------------------------------------------------- reading

    // For the bulk-send template picker.
    public List<WhatsAppTemplateResponse> listActive() {

        Employee employee = currentUserService.getCurrentEmployee();

        if (!accessControlService.hasPermission(employee, WHATSAPP_SEND)
                && !accessControlService.hasPermission(employee, WHATSAPP_TEMPLATE_MANAGE)) {
            throw new AccessDeniedException("You do not have permission to use WhatsApp templates.");
        }

        return templateRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .map(this::toResponse)
                .filter(t -> t.issues().isEmpty())
                .toList();
    }

    // Admin list: active and inactive.
    public List<WhatsAppTemplateResponse> listAll() {
        requireManage();
        return templateRepository.findAllByOrderByIsActiveDescNameAsc().stream().map(this::toResponse).toList();
    }

    public WhatsAppTemplateResponse get(Long id) {
        requireManage();
        return toResponse(load(id));
    }

    // Used by BulkWhatsAppService when a batch is created.
    public WhatsAppTemplate requireUsable(Long id) {

        WhatsAppTemplate template = load(id);

        if (!Boolean.TRUE.equals(template.getIsActive())) {
            throw new IllegalArgumentException("That WhatsApp template is no longer active.");
        }

        List<String> issues = WhatsAppTemplateRenderer.validate(template.getBody());

        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("That WhatsApp template cannot be used: " + String.join("; ", issues));
        }

        return template;
    }

    // Every supported merge field, for the admin "Insert field" picker - the
    // same closed set the renderer accepts.
    public List<Map<String, String>> fields() {

        List<Map<String, String>> out = new ArrayList<>();

        EmailPlaceholderRenderer.FIELDS.forEach((key, label) -> out.add(Map.of(
                "key", key,
                "label", label,
                "token", "{{" + key + "}}",
                "note", key.equals("opportunityTitle") ? "Only available when messaging opportunities" : "")));

        return out;
    }

    // ---------------------------------------------------------------- writing

    @Transactional
    public WhatsAppTemplateResponse create(WhatsAppTemplateRequest request) {

        Employee employee = requireManage();

        String body = normalizeBody(request.getBody());
        rejectIfInvalid(body);

        WhatsAppTemplate saved = templateRepository.save(WhatsAppTemplate.builder()
                .name(request.getName().trim())
                .description(trimToNull(request.getDescription()))
                .body(body)
                .version(1)
                .isActive(true)
                .createdByEmployeeId(employee.getId())
                .createdByName(employee.getName())
                .updatedByEmployeeId(employee.getId())
                .build());

        return toResponse(saved);
    }

    @Transactional
    public WhatsAppTemplateResponse update(Long id, WhatsAppTemplateRequest request) {

        Employee employee = requireManage();

        WhatsAppTemplate template = load(id);
        String body = normalizeBody(request.getBody());

        rejectIfInvalid(body);

        if (!Objects.equals(template.getBody(), body)) {
            template.setVersion(template.getVersion() == null ? 2 : template.getVersion() + 1);
        }

        template.setName(request.getName().trim());
        template.setDescription(trimToNull(request.getDescription()));
        template.setBody(body);
        template.setUpdatedByEmployeeId(employee.getId());

        return toResponse(templateRepository.save(template));
    }

    @Transactional
    public void deactivate(Long id) {

        requireManage();

        WhatsAppTemplate template = load(id);
        template.setIsActive(false);
        templateRepository.save(template);
    }

    @Transactional
    public void activate(Long id) {

        requireManage();

        WhatsAppTemplate template = load(id);
        rejectIfInvalid(template.getBody());
        template.setIsActive(true);
        templateRepository.save(template);
    }

    // ---------------------------------------------------------------- preview

    public WhatsAppTemplatePreviewResponse preview(WhatsAppTemplatePreviewRequest request) {

        Employee employee = requireManage();

        String body = request.getBody() == null ? "" : normalizeBody(request.getBody());

        Lead lead;
        Opportunity opportunity = null;
        boolean sample = request.getRecordId() == null;

        if (sample) {
            // Sample data has every field, including an opportunity title.
            opportunity = WhatsAppTemplateRenderer.sampleOpportunity();
            lead = opportunity.getLead();
        } else if (request.getTarget() == BulkEmailTarget.OPPORTUNITY) {
            opportunity = opportunityRepository.findById(request.getRecordId())
                    .orElseThrow(() -> new ResourceNotFoundException("Record not found"));
            lead = opportunity.getLead();
            assertMayView(employee, lead, OPPORTUNITY_VIEW);
        } else {
            lead = leadRepository.findById(request.getRecordId())
                    .orElseThrow(() -> new ResourceNotFoundException("Record not found"));
            assertMayView(employee, lead, LEAD_VIEW);
        }

        EmailPlaceholderRenderer.Rendered rendered = WhatsAppTemplateRenderer.render(body, lead, opportunity);

        Set<EmailPlaceholderRenderer.FieldValue> fields =
                new LinkedHashSet<>(EmailPlaceholderRenderer.fieldValues(lead, opportunity).values());

        return new WhatsAppTemplatePreviewResponse(rendered.text(), rendered.issues(), sample, new ArrayList<>(fields));
    }

    // -------------------------------------------------------------- helpers

    private void assertMayView(Employee employee, Lead lead, String permission) {

        Long owner = lead != null && lead.getAssignedEmployee() != null ? lead.getAssignedEmployee().getId() : null;

        try {
            accessControlService.assertCanAccess(employee, permission, owner);
        } catch (AccessDeniedException e) {
            // Same answer as a missing record: reveals nothing.
            throw new ResourceNotFoundException("Record not found");
        }
    }

    private Employee requireManage() {
        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, WHATSAPP_TEMPLATE_MANAGE);
        return employee;
    }

    private WhatsAppTemplate load(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WhatsApp template not found"));
    }

    private void rejectIfInvalid(String body) {

        List<String> issues = WhatsAppTemplateRenderer.validate(body);

        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("The template cannot be saved: " + String.join("; ", issues) + ".");
        }
    }

    private WhatsAppTemplateResponse toResponse(WhatsAppTemplate t) {
        return WhatsAppTemplateResponse.of(t, WhatsAppTemplateRenderer.validate(t.getBody()));
    }

    // Unix newlines; surrounding whitespace removed.
    private static String normalizeBody(String body) {
        return body == null ? "" : body.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
