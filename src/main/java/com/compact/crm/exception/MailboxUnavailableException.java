package com.compact.crm.exception;

// The signed-in employee's own mailbox cannot be used to send right now, and
// the fix is theirs to make (connect one, reconnect it, or use the mailbox the
// batch was started from). Mapped to 409 in GlobalExceptionHandler, with a
// user-facing message that never contains a token or provider payload. There
// is deliberately no fallback to a shared account or another employee's mailbox.
//
// Codes: MAILBOX_NOT_CONNECTED, MAILBOX_RECONNECT_REQUIRED, MAILBOX_MISMATCH,
// MAILBOX_BLOCKED.
public class MailboxUnavailableException extends RuntimeException {

    public static final String NOT_CONNECTED = "MAILBOX_NOT_CONNECTED";
    public static final String RECONNECT_REQUIRED = "MAILBOX_RECONNECT_REQUIRED";
    public static final String MISMATCH = "MAILBOX_MISMATCH";
    public static final String BLOCKED = "MAILBOX_BLOCKED";

    private final String code;

    public MailboxUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
