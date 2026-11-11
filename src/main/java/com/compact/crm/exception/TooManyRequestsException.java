package com.compact.crm.exception;

// 429 (see GlobalExceptionHandler). Raised by security.PublicLeadRateLimiter
// when a caller (identified by IP) exceeds the allowed submission rate on the
// unauthenticated public lead endpoint, and by bulk email when the sender's
// mailbox reaches its pacing or daily limit (service.BulkEmailService). When
// retryAfterSeconds is set it is sent as a Retry-After header, so the client
// knows when trying again can succeed.
public class TooManyRequestsException extends RuntimeException {

    private final Integer retryAfterSeconds;

    public TooManyRequestsException(String message) {
        this(message, null);
    }

    public TooManyRequestsException(String message, Integer retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
