package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Public-form equivalent of LeadProductRequest - deliberately has no
// quantity field. The public enquiry form only lets a visitor pick which
// products they're interested in, not a quantity; PublicLeadService applies
// a fixed quantity of 1 for every selected product.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PublicLeadProductRequest {

    @NotNull(message = "Please select a valid product.")
    private Long productId;
}
