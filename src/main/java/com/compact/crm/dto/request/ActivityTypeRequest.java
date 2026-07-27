package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ActivityTypeRequest {

    @NotBlank(message = "Activity name is required")
    private String name;
}