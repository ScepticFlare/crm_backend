package com.compact.crm.controller;

import com.compact.crm.dto.request.WhatsAppBulkConsentRequest;
import com.compact.crm.dto.request.WhatsAppConsentRequest;
import com.compact.crm.dto.response.PageResponse;
import com.compact.crm.dto.response.WhatsAppBulkConsentResult;
import com.compact.crm.dto.response.WhatsAppConsentAuditResponse;
import com.compact.crm.dto.response.WhatsAppConsentResponse;
import com.compact.crm.service.WhatsAppConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// WhatsApp consent. Authorization lives in WhatsAppConsentService:
//   /api/leads/{id}/whatsapp-consent  LEAD_VIEW to read, LEAD_MANAGE (in scope)
//                                     to record; re-opt-in after an opt-out is
//                                     admin-only
//   /api/whatsapp-consent/**          WHATSAPP_CONSENT_MANAGE (ADMIN only)
@RestController
@RequiredArgsConstructor
public class WhatsAppConsentController {

    private final WhatsAppConsentService consentService;

    @GetMapping("/api/leads/{leadId}/whatsapp-consent")
    public WhatsAppConsentResponse getForLead(@PathVariable Long leadId) {
        return consentService.getForLead(leadId);
    }

    @PostMapping("/api/leads/{leadId}/whatsapp-consent")
    public WhatsAppConsentResponse recordForLead(@PathVariable Long leadId,
                                                 @Valid @RequestBody WhatsAppConsentRequest request) {
        return consentService.recordForLead(leadId, request);
    }

    @GetMapping("/api/whatsapp-consent")
    public PageResponse<WhatsAppConsentResponse> list(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return consentService.list(query, page, size);
    }

    @PostMapping("/api/whatsapp-consent")
    public WhatsAppConsentResponse recordForPhone(@Valid @RequestBody WhatsAppConsentRequest request) {
        return consentService.recordForPhone(request);
    }

    @PostMapping("/api/whatsapp-consent/bulk-opt-in")
    public WhatsAppBulkConsentResult bulkOptIn(@Valid @RequestBody WhatsAppBulkConsentRequest request) {
        return consentService.bulkOptIn(request);
    }

    @GetMapping("/api/whatsapp-consent/audit")
    public PageResponse<WhatsAppConsentAuditResponse> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return consentService.audit(page, size);
    }
}
