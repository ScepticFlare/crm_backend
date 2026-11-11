package com.compact.crm.service.mail;

import com.compact.crm.enums.MailboxProvider;

// The OAuth 2.0 authorization-code (+ PKCE) side of one provider: building the
// consent URL, exchanging the code, refreshing, identifying the mailbox owner
// and revoking. Confidential web client: the client secret stays on the server.
public interface MailboxOAuthClient {

    MailboxProvider provider();

    String authorizationUrl(String state, String codeChallenge, String nonce, String loginHint);

    OAuthTokens exchangeCode(String code, String codeVerifier);

    OAuthTokens refresh(String refreshToken);

    // Who the tokens belong to (from the verified ID token, plus Graph /me for
    // Microsoft). expectedNonce is null on refresh.
    MailboxIdentity identify(OAuthTokens tokens, String expectedNonce);

    // Does the granted scope include the sending permission?
    boolean hasSendPermission(String grantedScopes);

    // Best effort; returns whether the provider confirmed the revocation.
    boolean revoke(String refreshToken);

    record MailboxIdentity(String subject, String email, String tenantId, String displayName) {
    }
}
