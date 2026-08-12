package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SalesStageRequest {

    @NotBlank(message = "Sales stage name is required")
    private String name;
}