package com.compact.crm.controller;

import com.compact.crm.dto.request.EmailSuppressionRequest;
import com.compact.crm.dto.response.EmailSuppressionAuditResponse;
import com.compact.crm.dto.response.EmailSuppressionPage;
import com.compact.crm.dto.response.EmailSuppressionResponse;
import com.compact.crm.service.EmailSuppressionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// The do-not-email list. Admin only: authorization (EMAIL_SUPPRESSION_MANAGE)
// lives in EmailSuppressionService, matching the other email endpoints.
@RestController
@RequestMapping("/api/email-suppressions")
@RequiredArgsConstructor
public class EmailSuppressionController {

    private final EmailSuppressionService suppressionService;

    @GetMapping
    public EmailSuppressionPage<EmailSuppressionResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return suppressionService.list(q, page, size);
    }

    @PostMapping
    public EmailSuppressionResponse add(@Valid @RequestBody EmailSuppressionRequest request) {
        return suppressionService.add(request);
    }

    // The justification is mandatory and stored in the audit trail.
    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id, @RequestParam String justification) {
        suppressionService.remove(id, justification);
    }

    @GetMapping("/audit")
    public EmailSuppressionPage<EmailSuppressionAuditResponse> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return suppressionService.audit(page, size);
    }
}
