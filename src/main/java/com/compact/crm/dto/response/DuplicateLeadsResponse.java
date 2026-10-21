package com.compact.crm.dto.response;

import java.util.List;

// Backs GET /api/leads/duplicates - a warning/review aid shown while a lead
// is being entered. Nothing is merged or blocked. `matches` are only leads
// the caller is allowed to view; `hiddenCount` says how many more exist that
// are outside the caller's scope (no details leak).
public record DuplicateLeadsResponse(List<Match> matches, long hiddenCount) {

    public record Match(
            Long id,
            String companyName,
            String contactPerson,
            String phone,
            String email,
            String status,
            String ownerName,
            List<String> matchedOn
    ) {
    }
}
