package com.compact.crm.service.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Predicate;

// Small, provider-neutral OAuth/OpenID helpers shared by GoogleOAuthClient and
// MicrosoftOAuthClient. No token, secret or response body is ever logged here -
// only the provider's short OAuth error code.
@Slf4j
final class OAuthSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final SecureRandom RANDOM = new SecureRandom();

    // Clock skew tolerated when checking an ID token's exp/iat.
    private static final long SKEW_SECONDS = 300;

    private OAuthSupport() {
    }

    // Without timeouts the JDK client waits forever on a stalled provider; for a
    // send, a read timeout is classified UNKNOWN (it may still have been sent).
    static JdkClientHttpRequestFactory timeoutFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }

    static String randomUrlSafe(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // PKCE S256 challenge for a verifier.
    static String s256(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    }

    static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // POSTs a form to a token endpoint. A 4xx carrying OAuth "invalid_grant" /
    // "interaction_required" means the credential itself is no longer usable.
    static OAuthTokens postToken(RestClient client, String uri, MultiValueMap<String, String> form, String provider) {

        Map<String, Object> body;

        try {

            body = client.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

        } catch (RestClientResponseException e) {

            String error = jsonField(e.getResponseBodyAsString(), "error");
            int status = e.getStatusCode().value();

            log.warn("{} token endpoint refused the request: HTTP {} error={}", provider, status, error);

            if (e.getStatusCode().is5xxServerError() || status == 408 || status == 429) {
                throw new OAuthException("provider_unavailable",
                        provider + " sign-in is temporarily unavailable. Please try again shortly.", false, true);
            }

            boolean invalidGrant = "invalid_grant".equals(error) || "interaction_required".equals(error)
                    || "consent_required".equals(error);

            if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                throw new OAuthException("client_misconfigured",
                        "The CRM's " + provider + " sign-in settings are not valid. Please contact an administrator.",
                        false, false);
            }

            throw new OAuthException(invalidGrant ? "invalid_grant" : safeCode(error),
                    invalidGrant
                            ? "The " + provider + " mailbox connection has expired or was revoked. Please reconnect it."
                            : provider + " refused the sign-in request.",
                    invalidGrant, false);

        } catch (RestClientException e) {

            log.warn("{} token endpoint could not be reached ({})", provider, e.getClass().getSimpleName());

            throw new OAuthException("provider_unavailable",
                    provider + " sign-in could not be reached. Please try again shortly.", false, true);
        }

        if (body == null || !(body.get("access_token") instanceof String access) || access.isBlank()) {
            throw new OAuthException("token_invalid", provider + " returned an unusable token response.", false, false);
        }

        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue()
                : body.get("expires_in") instanceof String s && s.matches("\\d+") ? Long.parseLong(s) : 3600;

        return new OAuthTokens(
                access,
                body.get("refresh_token") instanceof String r && !r.isBlank() ? r : null,
                expiresIn,
                body.get("scope") instanceof String sc ? sc : null,
                body.get("id_token") instanceof String id && !id.isBlank() ? id : null);
    }

    // The claims of a JWT, WITHOUT checking its signature. Only ever used on an
    // ID token received directly from the provider's token endpoint over TLS
    // (OpenID Connect Core 3.1.3.7 allows TLS server validation in place of the
    // signature check for exactly this case); never on a token from a browser.
    static Map<String, Object> claims(String jwt) {

        String[] parts = jwt == null ? new String[0] : jwt.split("\\.");

        if (parts.length < 2) {
            throw OAuthException.identity("The sign-in response did not contain a valid ID token.");
        }

        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw OAuthException.identity("The sign-in response did not contain a valid ID token.");
        }
    }

    // iss / aud / exp / nonce checks shared by both providers.
    static void validate(Map<String, Object> claims, Predicate<String> issuer, String clientId, String expectedNonce) {

        Object iss = claims.get("iss");

        if (!(iss instanceof String s) || !issuer.test(s)) {
            throw OAuthException.identity("The ID token was not issued by the expected provider.");
        }

        Object aud = claims.get("aud");
        boolean audienceOk = aud instanceof String a ? a.equals(clientId)
                : aud instanceof Collection<?> c && c.contains(clientId);

        if (!audienceOk) {
            throw OAuthException.identity("The ID token was issued for a different application.");
        }

        long now = Instant.now().getEpochSecond();

        if (!(claims.get("exp") instanceof Number exp) || exp.longValue() + SKEW_SECONDS < now) {
            throw OAuthException.identity("The ID token has expired.");
        }

        if (expectedNonce != null && !expectedNonce.equals(claims.get("nonce"))) {
            throw OAuthException.identity("The sign-in response does not belong to this connection attempt.");
        }
    }

    static String jsonField(String json, String field) {

        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            Object value = JSON.readValue(json, new TypeReference<Map<String, Object>>() { }).get(field);
            return value instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Only [a-z_] codes of reasonable length go anywhere near a URL or a log.
    static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z_]{1,40}") ? code : "oauth_error";
    }

    // Retry-After as seconds (delta-seconds or an HTTP date); null when absent.
    static Integer retryAfterSeconds(HttpHeaders headers) {

        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            // fall through to an HTTP date
        }

        try {
            long seconds = Duration.between(Instant.now(),
                    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            return (int) Math.max(0, Math.min(seconds, 86_400));
        } catch (Exception e) {
            return null;
        }
    }

    static String truncate(String text, int max) {
        return text == null ? null : text.length() > max ? text.substring(0, max) : text;
    }
}
