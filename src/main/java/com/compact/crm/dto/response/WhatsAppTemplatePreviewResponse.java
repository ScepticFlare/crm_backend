package com.compact.crm.dto.response;

import com.compact.crm.service.EmailPlaceholderRenderer;

import java.util.List;

// renderedText: the body as the recipient would receive it (unresolved tokens
// left visible so the problem can be seen); issues: why it could not be sent;
// sample: true when rendered with the built-in sample data, not a real record;
// fields: every supported field with the value it resolved to.
public record WhatsAppTemplatePreviewResponse(
        String renderedText,
        List<String> issues,
        boolean sample,
        List<EmailPlaceholderRenderer.FieldValue> fields
) {
}
