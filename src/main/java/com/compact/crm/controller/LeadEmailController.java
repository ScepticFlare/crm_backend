package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkKeepInTouchEmailRequest;
import com.compact.crm.dto.request.SendLeadEmailRequest;
import com.compact.crm.dto.response.BulkEmailEligibilityResponse;
import com.compact.crm.dto.response.BulkEmailResult;
import com.compact.crm.dto.response.EmailPreviewResponse;
import com.compact.crm.dto.response.LeadEmailSendResponse;
import com.compact.crm.enums.EmailTemplateType;
import com.compact.crm.service.LeadEmailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

// No @PreAuthorize - authorization is entirely inside LeadEmailService
// (EMAIL_SEND, scoped ALL/TEAM/OWN exactly like LEAD_MANAGE), matching the
// imperative service-level-check convention every other Lead-related
// controller (LeadController, FollowUpController) already follows, rather
// than the class-level @PreAuthorize convention used by master-data
// controllers.
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadEmailController {

    private final LeadEmailService leadEmailService;

    // Composer prefill: renders the chosen (or default, if templateId is
    // omitted) template's subject/body against this Lead, plus suggested
    // attachments. type distinguishes Product Brochure vs Keep in Touch.
    @GetMapping("/{id}/email/preview")
    public EmailPreviewResponse previewIndividualEmail(
            @PathVariable Long id,
            @RequestParam EmailTemplateType type,
            @RequestParam(required = false) Long templateId
    ) {
        return leadEmailService.previewIndividual(id, type, templateId);
    }

    // Sends exactly the subject/body/attachments the caller submits - see
    // dto.request.SendLeadEmailRequest for why the backend does not
    // re-render from templateId at send time.
    @PostMapping("/{id}/email/send")
    public LeadEmailSendResponse sendIndividualEmail(
            @PathVariable Long id,
            @Valid @RequestBody SendLeadEmailRequest request
    ) {
        return leadEmailService.sendIndividual(id, request);
    }

    // Backs the bulk Keep in Touch review step's "X selected - Y have valid
    // emails, Z do not" summary, before anything is actually sent.
    @GetMapping("/email/keep-in-touch/bulk/preview")
    public BulkEmailEligibilityResponse bulkEligibility(@RequestParam String ids) {
        return leadEmailService.bulkEligibility(parseIds(ids));
    }

    @PostMapping("/email/keep-in-touch/bulk")
    public BulkEmailResult sendBulkKeepInTouch(@Valid @RequestBody BulkKeepInTouchEmailRequest request) {
        return leadEmailService.sendBulkKeepInTouch(request);
    }

    private List<Long> parseIds(String ids) {

        if (ids == null || ids.isBlank()) {
            return List.of();
        }

        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

}
