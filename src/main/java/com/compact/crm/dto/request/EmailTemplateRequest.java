package com.compact.crm.dto.request;

import com.compact.crm.enums.EmailTemplateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplateRequest {

    @NotBlank(message = "Template name is required")
    private String name;

    @NotBlank(message = "Subject is required")
    private String subject;

    @NotBlank(message = "Body is required")
    private String body;

    @NotNull(message = "Template type is required")
    private EmailTemplateType type;

    // Optional - defaults to false when omitted. See
    // entity.EmailTemplate.isDefault / service.EmailTemplateService for how
    // setting this unsets any previous default of the same type.
    private Boolean isDefault;

}
