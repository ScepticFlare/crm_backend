package com.compact.crm.dto.request;

import com.compact.crm.enums.BulkEmailTarget;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Body for POST /api/bulk-email/preview, /batches and /send.
//
// ids are only CANDIDATES: every one is re-loaded and re-authorized server-side
// against the caller's own login (EMAIL_SEND scope), so an id the caller cannot
// access simply comes back excluded. Nothing here (no employee id, owner filter
// or role) can widen what the caller may email.
//
// /preview and /batches: subject/body are the batch's own editable copy with
// placeholders still in them (each recipient is rendered server-side); the shared
// template is never modified. /preview may leave them blank (recipient check
// only); /batches requires them. templateId must reference an active template.
//
// /send: only batchId (issued by the server by POST /batches) and ids (a chunk of
// at most 10, which must be a subset of the batch's own recipient list) are
// needed. target/subject/body/templateId are optional and, when present, must be
// IDENTICAL to what the batch was created with - the server always sends the
// batch's stored content, never what a later request says.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkEmailRequest {

    private BulkEmailTarget target;

    @NotEmpty(message = "Select at least one record")
    @Size(max = 100, message = "At most 100 records can be emailed in one batch")
    private List<Long> ids;

    @Size(max = 255, message = "Subject is too long (255 characters maximum)")
    private String subject;

    @Size(max = 20000, message = "Message is too long (20,000 characters maximum)")
    private String body;

    private Long templateId;

    // Server-issued id of an existing batch (/send only).
    @Size(max = 64, message = "Invalid batch id")
    private String batchId;
}
