package com.compact.crm.service.mail;

// The provider did not confirm a send (timeout, 5xx, I/O error after the
// request left): the email MAY have been sent. Callers must not resend it
// automatically. Still an IllegalStateException, so it maps to 502 like any
// other provider failure.
public class EmailOutcomeUnknownException extends IllegalStateException {

    public EmailOutcomeUnknownException(String message) {
        super(message);
    }
}
