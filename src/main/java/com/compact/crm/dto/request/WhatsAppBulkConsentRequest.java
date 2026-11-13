package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// ADMIN-only bulk opt-in import. A list of numbers alone is not consent: the
// admin must state where the consent was collected (source), when
// (consentedAt) and what documents it (evidence). Numbers that opted out are
// never changed by an import.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppBulkConsentRequest {

    @NotEmpty(message = "Add at least one phone number")
    @Size(max = 1000, message = "At most 1,000 numbers can be imported at once")
    private List<String> phones;

    @NotBlank(message = "Source is required")
    @Size(max = 50, message = "Source is too long (50 characters maximum)")
    private String source;

    @NotBlank(message = "Evidence is required - describe what documents this consent")
    @Size(max = 500, message = "Evidence is too long (500 characters maximum)")
    private String evidence;

    @NotNull(message = "The date the consent was given is required")
    private LocalDate consentedAt;
}
