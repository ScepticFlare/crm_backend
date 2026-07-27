package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IndustryRequest {

    @NotBlank(message = "Industry name is required")
    private String name;

}