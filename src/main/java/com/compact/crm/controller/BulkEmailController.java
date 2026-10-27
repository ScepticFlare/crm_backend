package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkEmailRequest;
import com.compact.crm.dto.response.BulkEmailBatchResponse;
import com.compact.crm.dto.response.BulkEmailPreviewResponse;
import com.compact.crm.dto.response.BulkEmailSendResponse;
import com.compact.crm.service.BulkEmailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Bulk email for Leads and Opportunities. No @PreAuthorize - like the other
// email endpoints, authorization (EMAIL_SEND, scoped ALL/TEAM/OWN, per record)
// lives entirely in BulkEmailService.
@RestController
@RequestMapping("/api/bulk-email")
@RequiredArgsConstructor
public class BulkEmailController {

    private final BulkEmailService bulkEmailService;

    // Review step: validates and authorizes the whole selection and, when a
    // subject/body are supplied, renders each recipient's own message.
    // Sends nothing.
    @PostMapping("/preview")
    public BulkEmailPreviewResponse preview(@Valid @RequestBody BulkEmailRequest request) {
        return bulkEmailService.preview(request);
    }

    // Creates a batch. The SERVER issues the batch id and pins the owner, target,
    // template, message and the whole recipient list (at most 100 records).
    @PostMapping("/batches")
    public BulkEmailBatchResponse createBatch(@Valid @RequestBody BulkEmailRequest request) {
        return bulkEmailService.createBatch(request);
    }

    // Sends one chunk (at most 10 records) of a batch the caller owns. The chunk
    // must be a subset of the batch's recipients and always sends the batch's own
    // message. Safe to repeat: recipients already handled are reported, never re-sent.
    @PostMapping("/send")
    public BulkEmailSendResponse send(@Valid @RequestBody BulkEmailRequest request) {
        return bulkEmailService.send(request);
    }

    // The whole batch as it stands: every originally selected record with its
    // recorded outcome (reload-safe; also the reconciliation point after a lost
    // or ambiguous response).
    @GetMapping("/batches/{batchId}")
    public BulkEmailSendResponse batch(@PathVariable String batchId) {
        return bulkEmailService.results(batchId);
    }
}
