package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Backs the "3 selected - 2 have valid emails, 1 does not" review step
// shown before a bulk Keep in Touch send actually fires. Computed read-only
// - no side effects, safe to call as the user adjusts their selection.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkEmailEligibilityResponse {

    private List<Long> eligibleIds;
    private List<Long> ineligibleIds;
    private int eligibleCount;
    private int ineligibleCount;

}
