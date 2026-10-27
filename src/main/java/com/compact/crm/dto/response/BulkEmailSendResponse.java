package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Per-recipient outcomes of one /send chunk (and, via GET /batches/{id}, of a
// whole batch). outcome is one of:
//   ACCEPTED    - the provider accepted it for delivery (NOT proof it was delivered)
//   FAILED      - the provider definitively rejected it; nothing was sent; safe to retry
//   UNKNOWN     - no reliable answer; it may have been sent; never retried automatically
//   IN_PROGRESS - another request is sending it right now
//   EXCLUDED    - validation/authorization removed it before sending (see detail)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkEmailSendResponse {

    private String batchId;
    private int accepted;
    private int failed;
    private int unknown;
    private int inProgress;
    private int excluded;
    private List<Result> results;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {

        private Long id;
        private String companyName;
        private String email;
        private String outcome;
        private String detail;

        // True when this answer is the stored outcome of an earlier submission
        // (a repeat/retry) - nothing was sent again.
        private boolean alreadyProcessed;
    }
}
