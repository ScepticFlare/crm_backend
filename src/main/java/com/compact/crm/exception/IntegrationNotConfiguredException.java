package com.compact.crm.exception;

// An optional integration (mailbox email sending, the public lead form's assignee) is
// not configured in this environment, so the feature that needs it cannot
// run. The CRM itself still starts without it - only the feature fails, with
// this exception, when invoked. Mapped to 503 in GlobalExceptionHandler; the
// message is user-facing, so it never names a secret or its value.
// Extends IllegalStateException so existing callers that treat a misconfigured
// integration as an IllegalStateException keep doing so.
public class IntegrationNotConfiguredException extends IllegalStateException {

    public IntegrationNotConfiguredException(String message) {
        super(message);
    }
}
