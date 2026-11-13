package com.compact.crm.enums;

// Outcome of one bulk-WhatsApp send request to the provider (WAHA), with the
// same meaning as EmailSendStatus:
//   PENDING  - claimed, provider call in flight (or the server died mid-call)
//   ACCEPTED - the provider returned 2xx: accepted for sending. NOT proof that
//              WhatsApp delivered it - see WhatsAppDeliveryStatus for that.
//   FAILED   - the provider definitively rejected it (4xx): nothing was sent,
//              safe to retry
//   UNKNOWN  - timeout / 5xx / I-O error: it may or may not have been sent.
//              Never auto-retried.
public enum WhatsAppSendStatus {
    PENDING,
    ACCEPTED,
    FAILED,
    UNKNOWN
}
