package com.compact.crm.dto.response;

import com.compact.crm.enums.BulkEmailTarget;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

// A server-issued batch: what POST /api/bulk-email/batches returns and the
// "batch" part of GET /api/bulk-email/batches/{id}. subject/body (the batch's own
// editable copy, placeholders intact) and recipientIds are filled on the GET so a
// browser that lost its page can resume; they are only ever shown to the batch's
// owner (or an admin).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkEmailBatchResponse {

    private String batchId;
    private BulkEmailTarget target;
    private Long templateId;
    private int requested;
    private int chunkSize;
    private int maxRecipients;
    private LocalDateTime createdAt;
    private String subject;
    private String body;
    private List<Long> recipientIds;
}
