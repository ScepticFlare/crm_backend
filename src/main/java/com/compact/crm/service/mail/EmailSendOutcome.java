package com.compact.crm.service.mail;

// What one send attempt tells us. Callers (bulk email above all) must tell
// "definitely not sent" from "maybe sent", because neither the Gmail API nor
// Microsoft Graph offers exactly-once delivery or an idempotency key:
//   ACCEPTED - the provider answered 2xx (accepted for delivery; NOT proof of delivery)
//   FAILED   - the provider refused it (4xx other than 408), or it was never
//              handed to the provider at all (providerCalled == false): not sent
//   UNKNOWN  - 5xx, 408, timeout or I/O error after the request was sent: it may
//              have been sent, so it must never be retried automatically
//
// signal says whether the rest of a batch should stop too:
//   THROTTLED       - provider rate/quota limit (or our own pacing): wait, then retry
//   AUTH_REJECTED   - the mailbox credential was refused: the employee must reconnect
//   MAILBOX_BLOCKED - the mailbox cannot send at all (no mailbox, sending disabled/blocked)
//   UNAVAILABLE     - the provider's sign-in service could not be reached: nothing was sent
public record EmailSendOutcome(
        Result result,
        Integer httpStatus,
        String detail,
        Signal signal,
        Integer retryAfterSeconds,
        boolean providerCalled
) {

    public enum Result { ACCEPTED, FAILED, UNKNOWN }

    public enum Signal { NONE, THROTTLED, AUTH_REJECTED, MAILBOX_BLOCKED, UNAVAILABLE }

    public EmailSendOutcome(Result result, Integer httpStatus, String detail) {
        this(result, httpStatus, detail, Signal.NONE, null, true);
    }

    public static EmailSendOutcome accepted() {
        return new EmailSendOutcome(Result.ACCEPTED, null, null);
    }

    // Refused before anything reached the provider's send endpoint.
    public static EmailSendOutcome notSent(Signal signal, String detail, Integer retryAfterSeconds) {
        return new EmailSendOutcome(Result.FAILED, null, detail, signal, retryAfterSeconds, false);
    }
}
