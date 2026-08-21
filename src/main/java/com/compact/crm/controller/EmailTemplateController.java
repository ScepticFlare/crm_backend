package com.compact.crm.controller;

import com.compact.crm.dto.request.EmailTemplateRequest;
import com.compact.crm.entity.EmailTemplate;
import com.compact.crm.enums.EmailTemplateType;
import com.compact.crm.service.EmailTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Listing/reading requires no @PreAuthorize - any authenticated employee
// needs to see the template library to compose a Lead email (see
// LeadEmailController). Only create/update/deactivate are authorization-
// checked, and that check happens inside EmailTemplateService
// (EMAIL_TEMPLATE_MANAGE) rather than here, matching the service-level-
// check convention used by Lead/Opportunity/Customer/FollowUp rather than
// the @PreAuthorize convention used by master-data controllers like
// ProductController - kept consistent with LeadEmailController below since
// this feature's authorization all lives in one place either way.
@RestController
@RequestMapping("/api/email-templates")
@RequiredArgsConstructor
public class EmailTemplateController {

    private final EmailTemplateService emailTemplateService;

    @PostMapping
    public EmailTemplate create(@Valid @RequestBody EmailTemplateRequest request) {
        return emailTemplateService.create(request);
    }

    @GetMapping
    public List<EmailTemplate> list(@RequestParam(required = false) EmailTemplateType type) {
        return emailTemplateService.listByType(type);
    }

    @GetMapping("/{id}")
    public EmailTemplate getById(@PathVariable Long id) {
        return emailTemplateService.getById(id);
    }

    @PutMapping("/{id}")
    public EmailTemplate update(@PathVariable Long id, @Valid @RequestBody EmailTemplateRequest request) {
        return emailTemplateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable Long id) {
        emailTemplateService.deactivate(id);
    }

    @PutMapping("/{id}/activate")
    public void activate(@PathVariable Long id) {
        emailTemplateService.activate(id);
    }

}
