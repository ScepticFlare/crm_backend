package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.compact.crm.service.EmailPlaceholderRenderer;

import java.util.List;
import java.util.Map;

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

    // Eligible lead id -> its company name and how each merge field resolves
    // for it, so the Keep in Touch draft can be shown filled in for a chosen
    // lead. Only for the eligible (authorized) ids.
    private Map<Long, Recipient> recipients;

    public record Recipient(String companyName, String contactPerson,
                            Map<String, EmailPlaceholderRenderer.FieldValue> mergeFields) {
    }

}
