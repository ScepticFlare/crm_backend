package com.compact.crm.service.mail;

// A token endpoint response. toString is overridden so a token can never end up
// in a log line or an exception message by accident.
public record OAuthTokens(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        String scope,
        String idToken
) {

    @Override
    public String toString() {
        return "OAuthTokens[accessToken=" + (accessToken == null ? "absent" : "present")
                + ", refreshToken=" + (refreshToken == null ? "absent" : "present")
                + ", expiresInSeconds=" + expiresInSeconds
                + ", scope=" + scope
                + ", idToken=" + (idToken == null ? "absent" : "present") + "]";
    }
}
