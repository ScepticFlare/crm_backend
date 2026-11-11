package com.compact.crm.service.mail;

import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.service.EmailAddresses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

// Google OAuth 2.0 for a confidential web client, authorization code + PKCE (S256).
// Scopes: "openid email" (only to learn which Google account approved - the
// verified address in the ID token) and gmail.send (send-only: the CRM can
// never read, list or delete mail). access_type=offline + prompt=consent so
// Google issues a refresh token on every connection.
@Slf4j
@Component
public class GoogleOAuthClient implements MailboxOAuthClient {

    static final String AUTHORIZE_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    static final String REVOKE_URI = "https://oauth2.googleapis.com/revoke";
    public static final String SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
    static final String SCOPES = "openid email " + SEND_SCOPE;

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final RestClient restClient;
    private final MailboxProperties properties;

    @Autowired
    public GoogleOAuthClient(MailboxProperties properties) {
        this(RestClient.builder().requestFactory(OAuthSupport.timeoutFactory()), properties);
    }

    // Package-private: tests bind a MockRestServiceServer to the builder.
    GoogleOAuthClient(RestClient.Builder builder, MailboxProperties properties) {
        this.restClient = builder.build();
        this.properties = properties;
    }

    @Override
    public MailboxProvider provider() {
        return MailboxProvider.GOOGLE;
    }

    @Override
    public String authorizationUrl(String state, String codeChallenge, String nonce, String loginHint) {

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(AUTHORIZE_URI)
                .queryParam("client_id", properties.getGoogleClientId())
                .queryParam("redirect_uri", properties.getGoogleRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPES)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("include_granted_scopes", "false")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256");

        if (loginHint != null && !loginHint.isBlank()) {
            uri.queryParam("login_hint", loginHint);
        }

        return uri.encode().build().toUriString();
    }

    @Override
    public OAuthTokens exchangeCode(String code, String codeVerifier) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.getGoogleRedirectUri());
        form.add("client_id", properties.getGoogleClientId());
        form.add("client_secret", properties.getGoogleClientSecret());
        form.add("code_verifier", codeVerifier);

        return OAuthSupport.postToken(restClient, TOKEN_URI, form, "Google");
    }

    @Override
    public OAuthTokens refresh(String refreshToken) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.getGoogleClientId());
        form.add("client_secret", properties.getGoogleClientSecret());

        return OAuthSupport.postToken(restClient, TOKEN_URI, form, "Google");
    }

    @Override
    public MailboxIdentity identify(OAuthTokens tokens, String expectedNonce) {

        Map<String, Object> claims = OAuthSupport.claims(tokens.idToken());

        OAuthSupport.validate(claims, ISSUERS::contains, properties.getGoogleClientId(), expectedNonce);

        if (!(claims.get("sub") instanceof String sub) || sub.isBlank()) {
            throw OAuthException.identity("Google did not identify the account.");
        }

        Object email = claims.get("email");
        Object verified = claims.get("email_verified");
        boolean isVerified = Boolean.TRUE.equals(verified) || "true".equals(verified);

        // On refresh Google may omit the email claims; only the subject is compared then.
        if (expectedNonce == null && email == null) {
            return new MailboxIdentity(sub, null, null, null);
        }

        if (!(email instanceof String address) || !EmailAddresses.isValid(address) || !isVerified) {
            throw OAuthException.identity("Google did not return a verified email address for this account.");
        }

        return new MailboxIdentity(sub, EmailAddresses.normalize(address), null, null);
    }

    @Override
    public boolean hasSendPermission(String grantedScopes) {
        return grantedScopes != null && Arrays.asList(grantedScopes.trim().split("\\s+")).contains(SEND_SCOPE);
    }

    // https://developers.google.com/identity/protocols/oauth2/web-server#tokenrevoke
    @Override
    public boolean revoke(String refreshToken) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);

        try {
            restClient.post().uri(REVOKE_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            // Already revoked/expired tokens answer 400 - the stored copy is deleted either way.
            log.info("Google token revocation was not confirmed ({})", e.getClass().getSimpleName());
            return false;
        }
    }
}
