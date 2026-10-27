package com.compact.crm.dto.request;

import com.compact.crm.enums.SuppressionReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Body for POST /api/email-suppressions (admin only).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmailSuppressionRequest {

    @NotBlank(message = "Email address is required")
    @Size(max = 254, message = "Email address is too long")
    private String email;

    @NotNull(message = "Reason is required (OPT_OUT, BOUNCE, COMPLAINT or MANUAL)")
    private SuppressionReason reason;

    @Size(max = 500, message = "Note is too long (500 characters maximum)")
    private String note;
}
