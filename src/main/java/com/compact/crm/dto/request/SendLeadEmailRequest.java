package com.compact.crm.dto.request;

import com.compact.crm.enums.EmailTemplateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

// Body for POST /api/leads/{id}/email/send. subject/body are the reviewed
// text the user saw in the composer. Any merge field still in it (one
// inserted after the preview) is resolved against this lead at send time,
// and an unresolved one is refused - see EmailPlaceholderRenderer. The
// backend does not re-render from templateId; templateId is carried through
// only so the send can be attributed to a template if it's worth knowing
// later; it is never re-fetched to override subject/body.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SendLeadEmailRequest {

    @NotNull(message = "Email type is required")
    private EmailTemplateType type;

    private Long templateId;

    @NotBlank(message = "Subject is required")
    private String subject;

    @NotBlank(message = "Body is required")
    private String body;

    private List<Long> documentIds;

}
