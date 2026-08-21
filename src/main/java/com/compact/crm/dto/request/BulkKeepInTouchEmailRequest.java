package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;

// Body for POST /api/leads/email/keep-in-touch/bulk. Bulk is Keep in Touch
// only (see service.LeadEmailService) - different Leads can have different
// interested products, so there is deliberately no bulk Product Brochure
// endpoint (per the business requirement).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkKeepInTouchEmailRequest {

    @NotEmpty(message = "At least one lead must be selected")
    private List<Long> leadIds;

    private Long templateId;

    @NotBlank(message = "Subject is required")
    private String subject;

    @NotBlank(message = "Body is required")
    private String body;

    private List<Long> documentIds;

}
