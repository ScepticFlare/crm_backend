package com.compact.crm.dto.request;

import com.compact.crm.enums.WhatsAppConsentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// Record an opt-in or opt-out for one number.
//   OPTED_IN  requires source, consentedAt (the documented date, not in the
//             future) and evidence (what proves it - form id, call note...).
//   OPTED_OUT source / date are optional (default: CRM_USER / today).
// `phone` is used only by the admin endpoint that records consent for a
// number directly; the lead endpoint always uses the lead's own phone.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppConsentRequest {

    @NotNull(message = "Choose opted in or opted out")
    private WhatsAppConsentStatus status;

    @Size(max = 50, message = "Source is too long (50 characters maximum)")
    private String source;

    @Size(max = 500, message = "Evidence is too long (500 characters maximum)")
    private String evidence;

    private LocalDate consentedAt;

    @Size(max = 40, message = "Phone number is too long")
    private String phone;
}
