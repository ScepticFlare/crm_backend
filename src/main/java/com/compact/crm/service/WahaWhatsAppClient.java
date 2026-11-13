package com.compact.crm.service;

import com.compact.crm.exception.IntegrationNotConfiguredException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// WhatsAppClient for a self-hosted WAHA (WhatsApp HTTP API, devlikeapro/waha)
// instance. Owns every WAHA-specific detail (verified against the WAHA docs,
// https://waha.devlike.pro/docs/):
//   * auth header      X-Api-Key: <WAHA_API_KEY>
//   * send text        POST /api/sendText {session, chatId:"<digits>@c.us", text, linkPreview:false}
//   * session status   GET  /api/sessions/{session} -> {status: WORKING | SCAN_QR_CODE | STARTING | FAILED | STOPPED ...}
//
// WAHA offers no idempotency key, so a send is never retried here: a timeout
// or 5xx is UNKNOWN (it may have gone out) and the caller must not resend it.
// The sendText response shape differs between WAHA engines, so the message id
// is read defensively (`id` as a string, or `id._serialized`); when neither is
// present the message is still ACCEPTED, just without delivery tracking.
//
// Optional integration: with WhatsApp disabled or no base URL / API key the CRM
// starts normally and every send is refused (503) - never attempted.
@Slf4j
@Service
public class WahaWhatsAppClient implements WhatsAppClient {

    static final String NOT_CONFIGURED_MESSAGE =
            "WhatsApp sending is not configured on this server. Please contact an administrator.";

    private static final Duration STATUS_CACHE = Duration.ofSeconds(10);

    // Digit runs long enough to be a phone number are masked out of any
    // provider error text before it is stored or shown.
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{7,}");
    private static final Pattern JSON_MESSAGE =
            Pattern.compile("\"(?:message|error)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final RestClient restClient;
    private final String session;
    private final boolean configured;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String cachedStatus;
    private volatile Instant cachedAt;

    @Autowired
    public WahaWhatsAppClient(
            @Value("${crm.whatsapp.enabled:true}") boolean enabled,
            @Value("${crm.whatsapp.waha.base-url:}") String baseUrl,
            @Value("${crm.whatsapp.waha.api-key:}") String apiKey,
            @Value("${crm.whatsapp.waha.session:default}") String session
    ) {
        this(RestClient.builder()
                        .requestFactory(timeoutFactory())
                        .baseUrl(trim(baseUrl).isEmpty() ? "http://localhost" : trim(baseUrl))
                        .defaultHeader("X-Api-Key", trim(apiKey))
                        .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE),
                session,
                enabled && !trim(baseUrl).isEmpty() && !trim(apiKey).isEmpty() && !trim(session).isEmpty(),
                Clock.systemUTC());

        if (!enabled) {
            log.warn("WhatsApp sending is DISABLED by configuration (crm.whatsapp.enabled=false).");
        } else if (!configured) {
            log.warn("WhatsApp sending is DISABLED: WAHA_BASE_URL and/or WAHA_API_KEY is not set. "
                    + "The CRM runs normally, but every WhatsApp send is refused until both are configured.");
        }
    }

    // Package-private: tests bind a MockRestServiceServer to the builder.
    WahaWhatsAppClient(RestClient.Builder builder, String session, boolean configured, Clock clock) {
        this.restClient = builder.build();
        this.session = trim(session).isEmpty() ? "default" : trim(session);
        this.configured = configured;
        this.clock = clock;
    }

    private static JdkClientHttpRequestFactory timeoutFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        // A read timeout is classified UNKNOWN: WAHA may still have sent it.
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    // ------------------------------------------------------------ configuration

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public void requireConfigured() {
        if (!configured) {
            throw new IntegrationNotConfiguredException(NOT_CONFIGURED_MESSAGE);
        }
    }

    // -------------------------------------------------------------- session

    @Override
    public String sessionStatus() {

        if (!configured) {
            return "NOT_CONFIGURED";
        }

        Instant now = clock.instant();
        String cached = cachedStatus;

        if (cached != null && cachedAt != null && now.isBefore(cachedAt.plus(STATUS_CACHE))) {
            return cached;
        }

        String status;

        try {
            JsonNode body = restClient.get()
                    .uri("/api/sessions/{session}", session)
                    .retrieve()
                    .body(JsonNode.class);

            String value = body != null && body.hasNonNull("status") ? body.get("status").asText() : null;
            status = value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase();

        } catch (RestClientResponseException e) {
            status = e.getStatusCode().value() == 404 ? "SESSION_NOT_FOUND"
                    : (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) ? "UNAUTHORIZED"
                    : "UNREACHABLE";
            log.warn("WAHA session status check failed (HTTP {})", e.getStatusCode().value());
        } catch (RestClientException e) {
            status = "UNREACHABLE";
            log.warn("WAHA session status check failed ({})", e.getClass().getSimpleName());
        }

        cachedStatus = status;
        cachedAt = now;

        return status;
    }

    @Override
    public boolean isReady() {
        return configured && "WORKING".equals(sessionStatus());
    }

    @Override
    public void requireReady() {

        requireConfigured();

        String status = sessionStatus();

        if (!"WORKING".equals(status)) {
            throw new IntegrationNotConfiguredException(
                    "WhatsApp is not connected (session status: " + status + "). "
                            + "An administrator must link or restart the WhatsApp session before messages can be sent.");
        }
    }

    // Forget the cached status (after a provider-level failure, so the next
    // check asks WAHA again).
    void invalidateStatus() {
        cachedStatus = null;
        cachedAt = null;
    }

    // ----------------------------------------------------------------- send

    @Override
    public SendOutcome sendText(String toDigits, String text) {

        if (!configured) {
            return new SendOutcome(Result.FAILED, null, null, "WhatsApp sending is not configured on this server", true);
        }

        if (toDigits == null || !toDigits.matches("^[1-9][0-9]{7,14}$")) {
            return new SendOutcome(Result.FAILED, null, null, "Invalid destination number", false);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session", session);
        payload.put("chatId", toDigits + "@c.us");
        payload.put("text", text);
        payload.put("linkPreview", false);

        try {

            String body = restClient.post()
                    .uri("/api/sendText")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            String messageId = messageId(body);

            return new SendOutcome(Result.ACCEPTED, messageId, null,
                    messageId == null ? "Accepted by WhatsApp provider (no message id returned - delivery cannot be tracked)" : null,
                    false);

        } catch (RestClientResponseException e) {

            int status = e.getStatusCode().value();
            String detail = describe(status, e.getResponseBodyAsString());

            if (status == 408 || e.getStatusCode().is5xxServerError()) {
                invalidateStatus();
                log.warn("WAHA send outcome unknown (HTTP {})", status);
                return new SendOutcome(Result.UNKNOWN, null, status, detail, true);
            }

            // 401/403: bad API key; 422: session not in a sending state; 429: provider
            // throttling. All are about the provider, not this recipient: stop the chunk.
            boolean providerLevel = status == 401 || status == 403 || status == 422 || status == 429;

            if (providerLevel) {
                invalidateStatus();
            }

            log.warn("WAHA rejected a send (HTTP {})", status);
            return new SendOutcome(Result.FAILED, null, status, detail, providerLevel);

        } catch (RestClientException e) {
            invalidateStatus();
            log.warn("WAHA send outcome unknown ({})", e.getClass().getSimpleName());
            return new SendOutcome(Result.UNKNOWN, null, null,
                    "No usable response from the WhatsApp provider (" + e.getClass().getSimpleName() + ")", true);
        }
    }

    // `id` as a string ("true_919...@c.us_3EB0..."), or an object carrying
    // `_serialized` (WEBJS-style). Anything else: no id.
    String messageId(String body) {

        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode id = root.get("id");

            if (id == null || id.isNull()) {
                return null;
            }

            String value = id.isTextual() ? id.asText()
                    : id.hasNonNull("_serialized") ? id.get("_serialized").asText() : null;

            return value == null || value.isBlank() || value.length() > 200 ? null : value.trim();

        } catch (Exception e) {
            return null;
        }
    }

    // Sanitized provider error: HTTP status + the provider's short message, with
    // phone-number-like digit runs masked. Never the request, headers or text sent.
    static String describe(int status, String responseBody) {

        String message = "";

        if (responseBody != null) {
            Matcher m = JSON_MESSAGE.matcher(responseBody);
            if (m.find()) {
                message = m.group(1).replace("\\\"", "\"");
            }
        }

        message = LONG_DIGITS.matcher(message).replaceAll("***");

        if (message.length() > 300) {
            message = message.substring(0, 300);
        }

        return "WhatsApp provider answered HTTP " + status + (message.isBlank() ? "" : ": " + message);
    }
}
