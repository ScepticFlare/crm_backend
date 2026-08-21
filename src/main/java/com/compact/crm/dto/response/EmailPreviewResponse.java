package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Response for the "prefill the composer" preview endpoints - subject/body
// already have this Lead's placeholders substituted (see
// service.LeadEmailService.renderForLead), ready for the user to edit
// before sending. suggestedDocumentIds are documents the backend thinks are
// relevant (e.g. brochures matching the Lead's products) - purely a
// suggestion, the frontend attachment picker still lets the user add/remove
// freely.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmailPreviewResponse {

    private Long templateId;
    private String subject;
    private String body;
    private List<Long> suggestedDocumentIds;

}
