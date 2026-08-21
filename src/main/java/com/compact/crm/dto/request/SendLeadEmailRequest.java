package com.compact.crm.dto.request;

import com.compact.crm.enums.EmailTemplateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

// Body for POST /api/leads/{id}/email/send. subject/body are the final,
// already-reviewed text the user saw in the composer (placeholders already
// substituted client-side from the preview response) - the backend does not
// re-render from templateId, it sends exactly what's here. templateId is
// carried through only so the send can be attributed to a template if it's
// worth knowing later; it is never re-fetched to override subject/body.
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
