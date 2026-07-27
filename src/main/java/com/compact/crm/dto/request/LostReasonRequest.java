package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LostReasonRequest {

    @NotBlank(message = "Lost reason is required")
    private String name;

}