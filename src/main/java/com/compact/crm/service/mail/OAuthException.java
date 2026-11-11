package com.compact.crm.service.mail;

// A failed OAuth step. code is a short, fixed, non-secret identifier (the
// provider's OAuth "error" value, or one of ours) that is safe to log and to put
// in the redirect back to the CRM. invalidGrant means the credential itself was
// refused (revoked consent, expired or reused refresh token, password change):
// reconnecting is the only fix. transientFailure means the provider could not
// be reached or answered 5xx: nothing changed, trying again later may work.
public class OAuthException extends RuntimeException {

    private final String code;
    private final boolean invalidGrant;
    private final boolean transientFailure;

    public OAuthException(String code, String message, boolean invalidGrant, boolean transientFailure) {
        super(message);
        this.code = code;
        this.invalidGrant = invalidGrant;
        this.transientFailure = transientFailure;
    }

    public static OAuthException identity(String message) {
        return new OAuthException("identity_invalid", message, false, false);
    }

    public String getCode() {
        return code;
    }

    public boolean isInvalidGrant() {
        return invalidGrant;
    }

    public boolean isTransientFailure() {
        return transientFailure;
    }
}
