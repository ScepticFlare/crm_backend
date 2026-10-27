package com.compact.crm.dto.request;

import com.compact.crm.enums.BulkEmailTarget;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Body for POST /api/bulk-email/preview and /send.
//
// ids are only CANDIDATES: every one is re-loaded and re-authorized server-side
// against the caller's own login (EMAIL_SEND scope), so an id the caller cannot
// access simply comes back excluded. Nothing here (no employee id, owner filter
// or role) can widen what the caller may email.
//
// subject/body are the batch's own editable copy with placeholders still in
// them (each recipient is rendered server-side); the shared template is never
// modified. Both may be blank for /preview (recipient check only) and are
// required for /send. templateId is informational (which approved template the
// batch started from) and must reference an active template when given.
//
// batchId is chosen by the client once per batch and reused for every chunk and
// retry: it is what makes a repeated submission idempotent.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkEmailRequest {

    @NotNull(message = "Target (LEAD or OPPORTUNITY) is required")
    private BulkEmailTarget target;

    @NotEmpty(message = "Select at least one record")
    @Size(max = 100, message = "At most 100 records can be emailed in one batch")
    private List<Long> ids;

    @Size(max = 255, message = "Subject is too long (255 characters maximum)")
    private String subject;

    @Size(max = 20000, message = "Message is too long (20,000 characters maximum)")
    private String body;

    private Long templateId;

    @Size(max = 64, message = "Invalid batch id")
    private String batchId;
}
