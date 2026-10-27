package com.compact.crm.enums;

// Outcome of one bulk-email send, as far as the provider response tells us:
//   PENDING  - claimed, provider call in flight (or the server died mid-call)
//   ACCEPTED - the provider returned 2xx. Accepted for delivery, NOT proof of delivery.
//   FAILED   - the provider definitively rejected it (4xx): nothing was sent, safe to retry
//   UNKNOWN  - timeout / 5xx / I-O error: it may or may not have been sent. Never auto-retried.
public enum EmailSendStatus {
    PENDING,
    ACCEPTED,
    FAILED,
    UNKNOWN
}
