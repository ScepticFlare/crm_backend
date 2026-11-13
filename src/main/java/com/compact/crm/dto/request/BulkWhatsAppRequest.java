package com.compact.crm.dto.request;

import com.compact.crm.enums.BulkEmailTarget;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Body for POST /api/bulk-whatsapp/preview, /batches and /send.
//
// ids are only CANDIDATES: every one is re-loaded and re-authorized server-side
// against the caller's own login (WHATSAPP_SEND scope). There is no message
// text here on purpose - employees choose an admin-made template (templateId)
// and the server renders it for each recipient.
//
// /preview:  target, ids, templateId optional (recipient check only without it)
// /batches:  target, ids, templateId required
// /send:     batchId + ids (a chunk, a subset of the batch); target/templateId
//            optional and, when present, must match the batch
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkWhatsAppRequest {

    private BulkEmailTarget target;

    @NotEmpty(message = "Select at least one record")
    @Size(max = 100, message = "At most 100 records can be messaged in one batch")
    private List<Long> ids;

    private Long templateId;

    @Size(max = 64, message = "Invalid batch id")
    private String batchId;
}
