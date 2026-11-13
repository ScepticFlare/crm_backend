package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

// Result of a chunk (POST /send) or the whole batch (GET /batches/{id}).
//
// outcome per recipient:
//   ACCEPTED      the provider accepted it for sending (NOT proof of delivery)
//   FAILED        definitely not sent - may be retried
//   UNKNOWN       may or may not have been sent - never retried
//   IN_PROGRESS   being sent right now
//   EXCLUDED      not sent; exclusionCode says why
//   NOT_ATTEMPTED not sent yet (exclusionCode PROVIDER_UNAVAILABLE when sending
//                 paused because the provider stopped responding) - safe to send
// deliveryStatus (ACCEPTED only, from provider webhooks): PENDING, SENT,
// DELIVERED, READ or FAILED; null until the provider reports.
// paused: sending stopped early in this chunk because of a provider problem.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkWhatsAppSendResponse {

    private String batchId;
    private int accepted;
    private int failed;
    private int unknown;
    private int inProgress;
    private int excluded;
    private int notAttempted;
    private boolean paused;
    private String pauseReason;
    private List<Result> results;
    private BulkWhatsAppBatchResponse batch;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {
        private Long id;
        private String companyName;
        private String phoneMasked;
        private String outcome;
        private String detail;
        private boolean alreadyProcessed;
        private String exclusionCode;
        private String deliveryStatus;
        private LocalDateTime deliveryUpdatedAt;
    }
}
