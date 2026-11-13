package com.compact.crm.dto.request;

import com.compact.crm.enums.BulkEmailTarget;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Admin template editor preview. Without target/recordId the body is rendered
// with clearly-labelled SAMPLE data; with them, with that real record's values
// (subject to the caller's access to it).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppTemplatePreviewRequest {

    @Size(max = 4096, message = "Message is too long (4,096 characters maximum)")
    private String body;

    private BulkEmailTarget target;

    private Long recordId;
}
