package com.compact.crm.dto.response;

import com.compact.crm.entity.WhatsAppTemplate;

import java.time.LocalDateTime;
import java.util.List;

// `issues`: template-level validation problems (empty when the template can be
// used). Only active templates without issues can be chosen for a bulk send.
public record WhatsAppTemplateResponse(
        Long id,
        String name,
        String description,
        String body,
        Integer version,
        Boolean isActive,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<String> issues
) {

    public static WhatsAppTemplateResponse of(WhatsAppTemplate t, List<String> issues) {
        return new WhatsAppTemplateResponse(t.getId(), t.getName(), t.getDescription(), t.getBody(),
                t.getVersion(), t.getIsActive(), t.getCreatedByName(), t.getCreatedAt(), t.getUpdatedAt(), issues);
    }
}
