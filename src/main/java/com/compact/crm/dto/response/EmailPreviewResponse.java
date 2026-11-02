package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.compact.crm.service.EmailPlaceholderRenderer;

import java.util.List;
import java.util.Map;

// Response for the "prefill the composer" preview endpoints - subject/body
// already have this Lead's merge fields substituted (see
// service.EmailPlaceholderRenderer), ready for the user to edit before
// sending. A field this Lead has no value for stays as its {{token}} and is
// listed in issues, so the composer can flag it before the send (which
// refuses it). suggestedDocumentIds are documents the backend thinks are
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
    private List<String> issues;

    // How each merge field resolves for this Lead (see
    // EmailPlaceholderRenderer.fieldValues), so the composer shows a field
    // still left as a {{token}} as "missing" instead of the raw token.
    private Map<String, EmailPlaceholderRenderer.FieldValue> mergeFields;

}
