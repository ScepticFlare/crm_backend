package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkWhatsAppRequest;
import com.compact.crm.dto.response.BulkWhatsAppBatchResponse;
import com.compact.crm.dto.response.BulkWhatsAppPreviewResponse;
import com.compact.crm.dto.response.BulkWhatsAppSendResponse;
import com.compact.crm.dto.response.WhatsAppAccessResponse;
import com.compact.crm.service.BulkWhatsAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// Bulk WhatsApp for Leads and Opportunities - mirrors BulkEmailController.
// No @PreAuthorize: authorization (WHATSAPP_SEND, scoped ALL/TEAM/OWN, per
// record) lives entirely in BulkWhatsAppService.
@RestController
@RequestMapping("/api/bulk-whatsapp")
@RequiredArgsConstructor
public class BulkWhatsAppController {

    private final BulkWhatsAppService bulkWhatsAppService;

    // The caller's own WHATSAPP_SEND grant plus (for those allowed to send) the
    // provider's readiness - drives whether the action is shown / enabled.
    @GetMapping("/access")
    public WhatsAppAccessResponse access() {
        return bulkWhatsAppService.access();
    }

    // Review step: validates and authorizes the whole selection and, with a
    // templateId, renders each recipient's own message. Sends nothing.
    @PostMapping("/preview")
    public BulkWhatsAppPreviewResponse preview(@Valid @RequestBody BulkWhatsAppRequest request) {
        return bulkWhatsAppService.preview(request);
    }

    // Creates a batch: the SERVER issues the id and pins owner, target, template
    // version + text and the recipient list. Sends nothing.
    @PostMapping("/batches")
    public BulkWhatsAppBatchResponse createBatch(@Valid @RequestBody BulkWhatsAppRequest request) {
        return bulkWhatsAppService.createBatch(request);
    }

    // Sends one chunk of a batch the caller owns. Safe to repeat: recipients
    // already handled are reported, never re-sent.
    @PostMapping("/send")
    public BulkWhatsAppSendResponse send(@Valid @RequestBody BulkWhatsAppRequest request) {
        return bulkWhatsAppService.send(request);
    }

    // The whole batch with recorded outcomes and delivery statuses (reload-safe;
    // the reconciliation point after a lost or ambiguous response).
    @GetMapping("/batches/{batchId}")
    public BulkWhatsAppSendResponse batch(@PathVariable String batchId) {
        return bulkWhatsAppService.results(batchId);
    }
}
