package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Create / update a CRM WhatsApp template (admin). The body uses {{field}}
// merge fields (see GET /api/whatsapp-templates/fields).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppTemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(max = 150, message = "Template name is too long (150 characters maximum)")
    private String name;

    @Size(max = 500, message = "Description is too long (500 characters maximum)")
    private String description;

    @NotBlank(message = "Message is required")
    @Size(max = 4096, message = "Message is too long (4,096 characters maximum)")
    private String body;
}
