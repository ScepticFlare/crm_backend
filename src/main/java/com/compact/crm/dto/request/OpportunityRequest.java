package com.compact.crm.dto.request;

import com.compact.crm.enums.LeadValidity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OpportunityRequest {

    @NotBlank(message = "Opportunity title is required")
    private String title;

    private Double productValue;

    private LocalDate expectedClosingDate;

    @NotBlank(message = "Sales stage is required")
    private String salesStage;

    @NotNull(message = "Please select a Validity.")
    private LeadValidity leadValidity;
}