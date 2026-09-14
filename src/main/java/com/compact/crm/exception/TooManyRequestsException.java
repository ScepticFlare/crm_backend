package com.compact.crm.exception;

// Raised by security.PublicLeadRateLimiter when a caller (identified by IP)
// exceeds the allowed submission rate on the unauthenticated public lead
// endpoint. See GlobalExceptionHandler for the 429 mapping.
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
