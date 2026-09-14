package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Public-form equivalent of LeadBatteryRequest - same shape as
// PublicLeadProductRequest (deliberately no quantity field; the public
// enquiry form only lets a visitor pick which batteries they're interested
// in, not a quantity). PublicLeadService applies a fixed quantity of 1 for
// every selected battery.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PublicLeadBatteryRequest {

    @NotNull(message = "Please select a valid battery.")
    private Long batteryId;
}
