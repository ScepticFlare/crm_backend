package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Bulk endpoints never silently skip unauthorized records without saying
// so: succeededIds is exactly what was affected, skippedIds is exactly
// what was requested but denied by AccessControlService (or not found).
// The caller can tell the two apart and show the user what actually
// happened.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkOperationResult {

    private List<Long> succeededIds;
    private List<Long> skippedIds;
}
