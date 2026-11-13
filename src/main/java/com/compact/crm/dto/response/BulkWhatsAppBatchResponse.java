package com.compact.crm.dto.response;

import com.compact.crm.enums.BulkEmailTarget;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

// A server-issued bulk WhatsApp batch. body / recipientIds are only filled
// when the whole batch is read back (GET /batches/{id}).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkWhatsAppBatchResponse {

    private String batchId;
    private BulkEmailTarget target;
    private Long templateId;
    private Integer templateVersion;
    private String templateName;
    private int requested;
    private int chunkSize;
    private int maxRecipients;
    private LocalDateTime createdAt;
    private String body;
    private List<Long> recipientIds;
}
