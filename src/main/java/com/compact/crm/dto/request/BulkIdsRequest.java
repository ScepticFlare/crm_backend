package com.compact.crm.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BulkIdsRequest {

    @NotEmpty(message = "At least one id is required")
    private List<Long> ids;
}
