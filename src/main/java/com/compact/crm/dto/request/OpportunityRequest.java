package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
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
}