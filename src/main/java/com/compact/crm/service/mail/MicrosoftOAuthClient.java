package com.compact.crm.service.mail;

import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.service.EmailAddresses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// Microsoft identity platform (v2.0 endpoints), authorization code + PKCE (S256),
// confidential web client. Delegated scopes: Mail.Send (send as the signed-in
// user only), User.Read (to read the signed-in user's own mailbox address from
// Graph /me - the ID token's username is not always the SMTP address),
// offline_access (refresh token), openid and profile. `profile` is required:
// without it Microsoft omits the `oid` claim (the user's object id, which Graph
// returns as /me `id`) from v2.0 ID tokens, and the account cannot be
// identified reliably - see learn.microsoft.com/entra/identity-platform/
// id-token-claims-reference. It is a standard OpenID scope (name / username /
// object id), not a Graph data permission. The tenant is
// MICROSOFT_TENANT_ID: the company's tenant id (single tenant, recommended),
// "organizations" (any work/school account), or "common" (work/school and
// personal Outlook.com accounts).
@Slf4j
@Component
public class MicrosoftOAuthClient implements MailboxOAuthClient {

    static final String LOGIN_BASE = "https://login.microsoftonline.com/";
    static final String GRAPH_ME = "https://graph.microsoft.com/v1.0/me?$select=id,mail,userPrincipalName,displayName";
    static final String SCOPES =
            "openid profile offline_access https://graph.microsoft.com/User.Read https://graph.microsoft.com/Mail.Send";

    // Tenant id of personal Microsoft accounts (Outlook.com, Hotmail, Live).
    static final String CONSUMER_TENANT = "9188040d-6c67-4c5b-b112-36a304b66dad";

    private static final Pattern ISSUER = Pattern.compile(
            "^https://login\\.microsoftonline\\.com/([0-9a-fA-F-]{36})/v2\\.0$");
    private static final Pattern GUID = Pattern.compile("^[0-9a-fA-F-]{36}$");

    private final RestClient restClient;
    private final MailboxProperties properties;

    @Autowired
    public MicrosoftOAuthClient(MailboxProperties properties) {
        this(RestClient.builder().requestFactory(OAuthSupport.timeoutFactory()), properties);
    }

    MicrosoftOAuthClient(RestClient.Builder builder, MailboxProperties properties) {
        this.restClient = builder.build();
        this.properties = properties;
    }

    @Override
    public MailboxProvider provider() {
        return MailboxProvider.MICROSOFT;
    }

    private String endpoint(String name) {
        return LOGIN_BASE + properties.getMicrosoftTenantId() + "/oauth2/v2.0/" + name;
    }

    @Override
    public String authorizationUrl(String state, String codeChallenge, String nonce, String loginHint) {

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(endpoint("authorize"))
                .queryParam("client_id", properties.getMicrosoftClientId())
                .queryParam("redirect_uri", properties.getMicrosoftRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("response_mode", "query")
                .queryParam("scope", SCOPES)
                .queryParam("prompt", "select_account")
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
        form.add("redirect_uri", properties.getMicrosoftRedirectUri());
        form.add("client_id", properties.getMicrosoftClientId());
        form.add("client_secret", properties.getMicrosoftClientSecret());
        form.add("code_verifier", codeVerifier);
        form.add("scope", SCOPES);

        return OAuthSupport.postToken(restClient, endpoint("token"), form, "Microsoft");
    }

    @Override
    public OAuthTokens refresh(String refreshToken) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.getMicrosoftClientId());
        form.add("client_secret", properties.getMicrosoftClientSecret());
        form.add("scope", SCOPES);

        return OAuthSupport.postToken(restClient, endpoint("token"), form, "Microsoft");
    }

    @Override
    public MailboxIdentity identify(OAuthTokens tokens, String expectedNonce) {

        Map<String, Object> claims = OAuthSupport.claims(tokens.idToken());

        OAuthSupport.validate(claims, iss -> {
            var m = ISSUER.matcher(iss);
            return m.matches() && m.group(1).equalsIgnoreCase(String.valueOf(claims.get("tid")));
        }, properties.getMicrosoftClientId(), expectedNonce);

        String tid = String.valueOf(claims.get("tid")).toLowerCase(Locale.ROOT);

        requireAllowedTenant(tid);

        boolean consumerTenant = CONSUMER_TENANT.equals(tid);

        // Work/school accounts are identified by `oid` - the same id Graph /me
        // returns - and it MUST be present (it is, with the `profile` scope).
        // Falling back to the per-app pairwise `sub` would make the Graph
        // identity check below fail for every such account. Personal accounts
        // are not compared with Graph, so `sub` is an acceptable fallback there.
        // The same rule runs at connect and at every refresh, so the stored
        // subject is always compared like with like.
        Object oid = claims.get("oid") != null ? claims.get("oid") : consumerTenant ? claims.get("sub") : null;

        if (!(oid instanceof String subject) || subject.isBlank()) {
            log.warn("Microsoft ID token has no oid claim - is the 'profile' scope granted?");
            throw OAuthException.identity("Microsoft did not identify the account.");
        }

        // On refresh only the account id is compared - no Graph call.
        if (expectedNonce == null) {
            return new MailboxIdentity(subject, null, tid, null);
        }

        Map<String, Object> me;

        try {
            me = restClient.get().uri(GRAPH_ME)
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        } catch (RestClientException e) {
            log.warn("Microsoft Graph /me could not be read ({})", e.getClass().getSimpleName());
            throw new OAuthException("profile_unavailable",
                    "Microsoft did not return the mailbox address. Please try again.", false, true);
        }

        if (me == null) {
            throw OAuthException.identity("Microsoft did not return the mailbox address.");
        }

        boolean consumer = consumerTenant;

        // A work/school account's Graph id IS its object id; a personal
        // account's Graph id uses a different format, so it is not compared.
        if (!consumer && !subject.equalsIgnoreCase(String.valueOf(me.get("id")))) {
            throw OAuthException.identity("The Microsoft profile does not match the signed-in account.");
        }

        String mail = me.get("mail") instanceof String m && EmailAddresses.isValid(m) ? m : null;

        if (mail == null && consumer && me.get("userPrincipalName") instanceof String upn && EmailAddresses.isValid(upn)) {
            mail = upn;
        }

        if (mail == null) {
            throw new OAuthException("no_mailbox",
                    "This Microsoft account has no Exchange Online mailbox, so it cannot send email.", false, false);
        }

        String name = me.get("displayName") instanceof String d && !d.isBlank() ? OAuthSupport.truncate(d, 100) : null;

        return new MailboxIdentity(subject, EmailAddresses.normalize(mail), tid, name);
    }

    private void requireAllowedTenant(String tid) {

        String configured = properties.getMicrosoftTenantId().toLowerCase(Locale.ROOT);

        boolean allowed = switch (configured) {
            case "common" -> true;
            case "organizations" -> !CONSUMER_TENANT.equals(tid);
            case "consumers" -> CONSUMER_TENANT.equals(tid);
            default -> !GUID.matcher(configured).matches() || configured.equals(tid);
        };

        if (!allowed) {
            throw new OAuthException("tenant_not_allowed",
                    "This Microsoft account is not part of the organisation this CRM accepts.", false, false);
        }
    }

    @Override
    public boolean hasSendPermission(String grantedScopes) {
        return grantedScopes != null && Arrays.stream(grantedScopes.trim().split("\\s+"))
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(s -> s.equals("mail.send") || s.endsWith("/mail.send"));
    }

    // Microsoft offers no endpoint for an app to revoke one delegated refresh
    // token. Disconnecting deletes the CRM's copy (so the CRM can no longer
    // send); the user can also remove the app's consent at
    // https://myapps.microsoft.com (work) or https://account.live.com/consent/Manage (personal).
    @Override
    public boolean revoke(String refreshToken) {
        return false;
    }
}
