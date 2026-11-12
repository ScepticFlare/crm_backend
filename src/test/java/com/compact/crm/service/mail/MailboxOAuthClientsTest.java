package com.compact.crm.service.mail;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Google and Microsoft OAuth against mocked token / Graph endpoints - no real provider call. */
class MailboxOAuthClientsTest {

    private static Map<String, String> query(String url) {
        UriComponents u = UriComponentsBuilder.fromUriString(url).build();
        return u.getQueryParams().toSingleValueMap().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, e -> URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8)));
    }

    private static String tokenJson(String idToken, String scope, boolean withRefresh) {
        return "{\"access_token\":\"at-1\",\"expires_in\":3599,\"token_type\":\"Bearer\",\"scope\":\"" + scope + "\""
                + (withRefresh ? ",\"refresh_token\":\"rt-1\"" : "")
                + (idToken == null ? "" : ",\"id_token\":\"" + idToken + "\"") + "}";
    }

    // ================================================================= Google

    @Test
    void google_consentUrl_usesCodeFlowWithPkceS256_stateNonce_andOnlyTheSendScope() {

        GoogleOAuthClient google = new GoogleOAuthClient(RestClient.builder(), MailTestSupport.properties());

        String verifier = OAuthSupport.randomUrlSafe(64);
        Map<String, String> q = query(google.authorizationUrl("st-1", OAuthSupport.s256(verifier), "n-1", "asha@company.test"));

        assertThat(q).containsEntry("response_type", "code")
                .containsEntry("client_id", "google-client")
                .containsEntry("redirect_uri", "https://crm.test/api/email-accounts/oauth/google/callback")
                .containsEntry("state", "st-1")
                .containsEntry("nonce", "n-1")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("code_challenge", OAuthSupport.s256(verifier))
                .containsEntry("access_type", "offline")
                .containsEntry("login_hint", "asha@company.test");
        assertThat(q.get("scope").split(" ")).containsExactlyInAnyOrder(
                "openid", "email", "https://www.googleapis.com/auth/gmail.send");
        // no implicit / token flow, and no read scopes
        assertThat(q.get("scope")).doesNotContain("readonly").doesNotContain("mail.google.com");
    }

    @Test
    void google_codeExchange_sendsTheVerifier_andTheIdTokenIdentifiesTheMailbox() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleOAuthClient google = new GoogleOAuthClient(builder, MailTestSupport.properties());

        String id = MailTestSupport.idToken(MailTestSupport.googleClaims("g-sub-1", "Asha@Company.test", "n-1"));

        server.expect(requestTo(GoogleOAuthClient.TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "authorization_code", "code", "the-code", "code_verifier", "the-verifier",
                        "client_id", "google-client", "client_secret", "google-secret",
                        "redirect_uri", "https://crm.test/api/email-accounts/oauth/google/callback")))
                .andRespond(withSuccess(tokenJson(id, "openid https://www.googleapis.com/auth/gmail.send", true),
                        MediaType.APPLICATION_JSON));

        OAuthTokens tokens = google.exchangeCode("the-code", "the-verifier");

        assertThat(tokens.refreshToken()).isEqualTo("rt-1");
        assertThat(tokens.toString()).doesNotContain("rt-1").doesNotContain("at-1");
        assertThat(google.hasSendPermission(tokens.scope())).isTrue();

        MailboxOAuthClient.MailboxIdentity who = google.identify(tokens, "n-1");
        assertThat(who.subject()).isEqualTo("g-sub-1");
        assertThat(who.email()).isEqualTo("asha@company.test");
    }

    @Test
    void google_idTokenChecks_rejectAWrongNonceAudienceIssuerExpiryOrUnverifiedEmail() {

        GoogleOAuthClient google = new GoogleOAuthClient(RestClient.builder(), MailTestSupport.properties());

        Map<String, Object> ok = MailTestSupport.googleClaims("s", "a@c.test", "n-1");

        assertThat(google.identify(tokensWith(ok), "n-1").email()).isEqualTo("a@c.test");

        assertThatThrownBy(() -> google.identify(tokensWith(ok), "another-nonce"))
                .isInstanceOf(OAuthException.class).hasMessageContaining("connection attempt");

        Map<String, Object> aud = new java.util.HashMap<>(ok);
        aud.put("aud", "someone-elses-app");
        assertThatThrownBy(() -> google.identify(tokensWith(aud), "n-1")).isInstanceOf(OAuthException.class);

        Map<String, Object> iss = new java.util.HashMap<>(ok);
        iss.put("iss", "https://evil.example");
        assertThatThrownBy(() -> google.identify(tokensWith(iss), "n-1")).isInstanceOf(OAuthException.class);

        Map<String, Object> exp = new java.util.HashMap<>(ok);
        exp.put("exp", Instant.now().minusSeconds(3600).getEpochSecond());
        assertThatThrownBy(() -> google.identify(tokensWith(exp), "n-1")).isInstanceOf(OAuthException.class);

        Map<String, Object> unverified = new java.util.HashMap<>(ok);
        unverified.put("email_verified", false);
        assertThatThrownBy(() -> google.identify(tokensWith(unverified), "n-1")).isInstanceOf(OAuthException.class);
    }

    @Test
    void google_grantedScopeWithoutGmailSend_isDetected() {
        GoogleOAuthClient google = new GoogleOAuthClient(RestClient.builder(), MailTestSupport.properties());
        assertThat(google.hasSendPermission("openid email")).isFalse();
        assertThat(google.hasSendPermission(null)).isFalse();
    }

    @Test
    void google_refresh_invalidGrantMeansReconnect_and5xxIsTransient() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleOAuthClient google = new GoogleOAuthClient(builder, MailTestSupport.properties());

        server.expect(requestTo(GoogleOAuthClient.TOKEN_URI))
                .andExpect(content().formDataContains(Map.of("grant_type", "refresh_token", "refresh_token", "rt-old")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}"));

        assertThatThrownBy(() -> google.refresh("rt-old"))
                .isInstanceOfSatisfying(OAuthException.class, e -> {
                    assertThat(e.isInvalidGrant()).isTrue();
                    assertThat(e.getMessage()).doesNotContain("rt-old");
                });

        server.reset();
        server.expect(requestTo(GoogleOAuthClient.TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> google.refresh("rt-old"))
                .isInstanceOfSatisfying(OAuthException.class, e -> assertThat(e.isTransientFailure()).isTrue());
    }

    @Test
    void google_revocation_callsTheRevokeEndpoint() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleOAuthClient google = new GoogleOAuthClient(builder, MailTestSupport.properties());

        server.expect(requestTo(GoogleOAuthClient.REVOKE_URI))
                .andExpect(content().formDataContains(Map.of("token", "rt-1")))
                .andRespond(withSuccess());

        assertThat(google.revoke("rt-1")).isTrue();
        server.verify();
    }

    // ============================================================== Microsoft

    private static final String TENANT = "11111111-2222-3333-4444-555555555555";

    @Test
    void microsoft_consentUrl_usesTheTenant_pkce_andDelegatedMailSend() {

        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(RestClient.builder(), MailTestSupport.properties(TENANT, true));

        String url = ms.authorizationUrl("st", "ch", "nn", null);
        Map<String, String> q = query(url);

        assertThat(url).startsWith("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/authorize?");
        assertThat(q).containsEntry("response_type", "code").containsEntry("response_mode", "query")
                .containsEntry("code_challenge_method", "S256").containsEntry("state", "st").containsEntry("nonce", "nn");
        // `profile` is what makes Microsoft include the `oid` claim in the ID token
        assertThat(q.get("scope").split(" ")).containsExactlyInAnyOrder("openid", "profile", "offline_access",
                "https://graph.microsoft.com/User.Read", "https://graph.microsoft.com/Mail.Send");
    }

    @Test
    void microsoft_identity_comesFromTheIdTokenAndGraphMe() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(builder, MailTestSupport.properties(TENANT, true));

        String oid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String id = MailTestSupport.idToken(MailTestSupport.microsoftClaims(oid, TENANT, "n-1"));

        server.expect(requestTo(MicrosoftOAuthClient.GRAPH_ME))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer at-1"))
                .andRespond(withSuccess("{\"id\":\"" + oid + "\",\"mail\":\"Ravi@Company.test\","
                        + "\"userPrincipalName\":\"ravi@company.onmicrosoft.com\",\"displayName\":\"Ravi\"}", MediaType.APPLICATION_JSON));

        MailboxOAuthClient.MailboxIdentity who = ms.identify(
                new OAuthTokens("at-1", "rt-1", 3600, "Mail.Send User.Read", id), "n-1");

        assertThat(who.subject()).isEqualTo(oid);
        assertThat(who.email()).isEqualTo("ravi@company.test");
        assertThat(who.tenantId()).isEqualTo(TENANT);
        assertThat(ms.hasSendPermission("https://graph.microsoft.com/Mail.Send https://graph.microsoft.com/User.Read")).isTrue();
        assertThat(ms.hasSendPermission("User.Read")).isFalse();
    }

    @Test
    void microsoft_anAccountFromAnotherTenant_isRefused() {

        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(RestClient.builder(), MailTestSupport.properties(TENANT, true));

        String other = "99999999-2222-3333-4444-555555555555";
        String id = MailTestSupport.idToken(MailTestSupport.microsoftClaims("oid", other, "n"));

        assertThatThrownBy(() -> ms.identify(new OAuthTokens("at", "rt", 3600, "Mail.Send", id), "n"))
                .isInstanceOfSatisfying(OAuthException.class, e -> assertThat(e.getCode()).isEqualTo("tenant_not_allowed"));
    }

    @Test
    void microsoft_anIssuerThatDoesNotMatchTheTokensTenant_isRefused() {

        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(RestClient.builder(), MailTestSupport.properties());

        Map<String, Object> claims = MailTestSupport.microsoftClaims("oid", TENANT, "n");
        claims.put("iss", "https://login.microsoftonline.com/99999999-2222-3333-4444-555555555555/v2.0");

        assertThatThrownBy(() -> ms.identify(new OAuthTokens("at", "rt", 3600, "Mail.Send",
                MailTestSupport.idToken(claims)), "n")).isInstanceOf(OAuthException.class);
    }

    @Test
    void microsoft_anAccountWithoutAMailbox_cannotBeConnected() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(builder, MailTestSupport.properties());

        String id = MailTestSupport.idToken(MailTestSupport.microsoftClaims("oid-1", TENANT, "n"));

        server.expect(requestTo(MicrosoftOAuthClient.GRAPH_ME))
                .andRespond(withSuccess("{\"id\":\"oid-1\",\"mail\":null,\"userPrincipalName\":\"x@tenant.onmicrosoft.com\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ms.identify(new OAuthTokens("at", "rt", 3600, "Mail.Send", id), "n"))
                .isInstanceOfSatisfying(OAuthException.class, e -> assertThat(e.getCode()).isEqualTo("no_mailbox"));
    }

    @Test
    void microsoft_refresh_interactionRequiredMeansReconnect() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(builder, MailTestSupport.properties(TENANT, true));

        server.expect(requestTo("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token"))
                .andExpect(content().formDataContains(Map.of("grant_type", "refresh_token", "client_id", "ms-client")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"interaction_required\",\"error_description\":\"AADSTS65001: consent revoked\"}"));

        assertThatThrownBy(() -> ms.refresh("rt"))
                .isInstanceOfSatisfying(OAuthException.class, e -> assertThat(e.isInvalidGrant()).isTrue());
    }

    @Test
    void microsoft_theCodeExchangeAndRefreshRequestTheSameScopes_includingProfile() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(builder, MailTestSupport.properties(TENANT, true));
        String tokenUri = "https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token";
        String ok = "{\"access_token\":\"at\",\"expires_in\":3600,\"scope\":\"https://graph.microsoft.com/Mail.Send\"}";

        server.expect(requestTo(tokenUri))
                .andExpect(content().formDataContains(Map.of("grant_type", "authorization_code",
                        "scope", MicrosoftOAuthClient.SCOPES, "code_verifier", "v",
                        "redirect_uri", "https://crm.test/api/email-accounts/oauth/microsoft/callback")))
                .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));
        server.expect(requestTo(tokenUri))
                .andExpect(content().formDataContains(Map.of("grant_type", "refresh_token", "scope", MicrosoftOAuthClient.SCOPES)))
                .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));

        ms.exchangeCode("c", "v");
        ms.refresh("rt");

        server.verify();
        assertThat(MicrosoftOAuthClient.SCOPES.split(" ")).contains("profile");
    }

    @Test
    void microsoft_aWorkAccountIdTokenWithoutOid_isRejected_beforeAnyGraphCall_neverMatchedOnSub() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();   // no Graph call expected
        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(builder, MailTestSupport.properties(TENANT, true));

        // what Microsoft issues when `profile` is not granted: sub (pairwise) but no oid
        Map<String, Object> claims = MailTestSupport.microsoftClaims("unused", TENANT, "n");
        claims.remove("oid");
        claims.put("sub", "pairwise-sub-value");
        OAuthTokens tokens = new OAuthTokens("at", "rt", 3600, "Mail.Send", MailTestSupport.idToken(claims));

        assertThatThrownBy(() -> ms.identify(tokens, "n"))
                .isInstanceOfSatisfying(OAuthException.class, e -> assertThat(e.getCode()).isEqualTo("identity_invalid"));
        assertThatThrownBy(() -> ms.identify(tokens, null)).isInstanceOf(OAuthException.class);   // refresh too
        server.verify();
    }

    @Test
    void microsoft_connectAndRefresh_identifyAWorkAccountByTheSameOid() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(builder, MailTestSupport.properties(TENANT, true));
        String oid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

        Map<String, Object> claims = MailTestSupport.microsoftClaims(oid, TENANT, "n");
        claims.put("sub", "pairwise-sub-value");

        server.expect(requestTo(MicrosoftOAuthClient.GRAPH_ME))
                .andRespond(withSuccess("{\"id\":\"" + oid + "\",\"mail\":\"ravi@company.test\"}", MediaType.APPLICATION_JSON));

        String atConnect = ms.identify(new OAuthTokens("at", "rt", 3600, "Mail.Send", MailTestSupport.idToken(claims)), "n").subject();

        Map<String, Object> refreshed = new java.util.HashMap<>(claims);
        refreshed.remove("nonce");
        String atRefresh = ms.identify(new OAuthTokens("at2", null, 3600, "Mail.Send", MailTestSupport.idToken(refreshed)), null).subject();

        assertThat(atConnect).isEqualTo(oid).isEqualTo(atRefresh);
        server.verify();
    }

    @Test
    void microsoft_aPersonalAccountWithoutOid_isIdentifiedBySub_andSendsFromItsUserPrincipalName() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(builder, MailTestSupport.properties("common", true));

        Map<String, Object> claims = MailTestSupport.microsoftClaims("unused", MicrosoftOAuthClient.CONSUMER_TENANT, "n");
        claims.remove("oid");
        claims.put("sub", "msa-sub");

        server.expect(requestTo(MicrosoftOAuthClient.GRAPH_ME))
                .andRespond(withSuccess("{\"id\":\"4a8f1a2b3c4d5e6f\",\"mail\":null,\"userPrincipalName\":\"Asha@Outlook.test\"}",
                        MediaType.APPLICATION_JSON));

        MailboxOAuthClient.MailboxIdentity who = ms.identify(
                new OAuthTokens("at", "rt", 3600, "Mail.Send", MailTestSupport.idToken(claims)), "n");

        assertThat(who.subject()).isEqualTo("msa-sub");
        assertThat(who.email()).isEqualTo("asha@outlook.test");
    }

    // One Microsoft 365 tenant can hold several verified domains (e.g. a .com and
    // a .in). Nothing is domain-specific: the single-tenant check is on the
    // tenant id, and each account is identified by its own Graph mailbox address.
    @Test
    void microsoft_accountsOnDifferentDomainsOfTheSameTenant_areBothAccepted_eachWithItsOwnAddress() {

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MicrosoftOAuthClient ms = new MicrosoftOAuthClient(builder, MailTestSupport.properties(TENANT, true));

        String inOid = "aaaaaaaa-0000-0000-0000-000000000001";
        String comOid = "aaaaaaaa-0000-0000-0000-000000000002";

        server.expect(requestTo(MicrosoftOAuthClient.GRAPH_ME))
                .andRespond(withSuccess("{\"id\":\"" + inOid + "\",\"mail\":\"Priya@CompactUPS.in\","
                        + "\"userPrincipalName\":\"priya@compactups.in\",\"displayName\":\"Priya\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(MicrosoftOAuthClient.GRAPH_ME))
                .andRespond(withSuccess("{\"id\":\"" + comOid + "\",\"mail\":\"rahul@compactups.com\","
                        + "\"userPrincipalName\":\"rahul@compactups.com\",\"displayName\":\"Rahul\"}", MediaType.APPLICATION_JSON));

        MailboxOAuthClient.MailboxIdentity in = ms.identify(new OAuthTokens("at-in", "rt", 3600, "Mail.Send",
                MailTestSupport.idToken(MailTestSupport.microsoftClaims(inOid, TENANT, "n-in"))), "n-in");
        MailboxOAuthClient.MailboxIdentity com = ms.identify(new OAuthTokens("at-com", "rt", 3600, "Mail.Send",
                MailTestSupport.idToken(MailTestSupport.microsoftClaims(comOid, TENANT, "n-com"))), "n-com");

        assertThat(in.email()).isEqualTo("priya@compactups.in");
        assertThat(in.subject()).isEqualTo(inOid);
        assertThat(com.email()).isEqualTo("rahul@compactups.com");
        assertThat(com.subject()).isEqualTo(comOid);
        assertThat(in.tenantId()).isEqualTo(TENANT).isEqualTo(com.tenantId());
        server.verify();
    }

    private static OAuthTokens tokensWith(Map<String, Object> claims) {
        return new OAuthTokens("at", "rt", 3600, "openid", MailTestSupport.idToken(claims));
    }
}
