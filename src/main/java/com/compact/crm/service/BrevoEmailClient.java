package com.compact.crm.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Base64;
import java.util.List;

// Sends Lead emails through Brevo's transactional HTTP API
// (POST https://api.brevo.com/v3/smtp/email) instead of SMTP - Render
// cannot open an outbound TCP connection to smtp-relay.brevo.com:587, but
// outbound HTTPS works fine. Owns every Brevo-specific concern (endpoint,
// auth header, JSON request shape, sender identity) so LeadEmailService
// stays a plain orchestrator that only knows "send this email" - the same
// separation DocumentStorageService draws between DocumentService and
// where bytes actually live.
@Slf4j
@Service
public class BrevoEmailClient {

    private static final String SEND_URI = "/smtp/email";
    private static final String EXPECTED_KEY_PREFIX = "xkeysib-";

    private final RestClient restClient;
    private final String fromAddress;
    private final String fromName;

    // Explicitly @Autowired: a second (package-private, test-only)
    // constructor also exists below, and Spring's implicit
    // single-constructor injection only applies when there's exactly one
    // non-default constructor - without this marker bean creation fails at
    // startup with "No default constructor found".
    @Autowired
    public BrevoEmailClient(
            @Value("${brevo.api-key}") String apiKey,
            @Value("${crm.mail.from-address}") String fromAddress,
            @Value("${crm.mail.from-name}") String fromName
    ) {
        this(RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", sanitizeApiKey(apiKey))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE), fromAddress, fromName);

        logApiKeyDiagnostics(apiKey);
    }

    // Strips accidental surrounding whitespace/newline - a common
    // copy-paste artifact when pasting a secret into a platform's env var
    // UI. Brevo has no tolerance for it and fails with a generic
    // "Key not found" rather than anything that points at whitespace.
    // Package-private and pure so it's directly unit-testable without
    // needing a live Brevo call.
    static String sanitizeApiKey(String apiKey) {
        return apiKey == null ? "" : apiKey.trim();
    }

    // TEMPORARY diagnostic for the "401 Key not found" investigation - logs
    // only the shape of the configured key (present/length/whitespace/
    // quotes/expected prefix), never the key itself. Safe to leave in (no
    // secret material is logged) but remove once BREVO_API_KEY on Render is
    // confirmed correct.
    private static void logApiKeyDiagnostics(String apiKey) {

        boolean blank = apiKey == null || apiKey.isBlank();
        String trimmed = blank ? "" : apiKey.trim();

        log.info(
                "Brevo API key diagnostics: present={}, length={}, hasSurroundingWhitespace={}, " +
                        "containsQuoteChar={}, startsWithExpectedPrefix={}, looksLikeUnresolvedPlaceholder={}",
                !blank,
                apiKey == null ? 0 : apiKey.length(),
                !blank && !apiKey.equals(trimmed),
                !blank && (apiKey.contains("\"") || apiKey.contains("'")),
                !blank && trimmed.startsWith(EXPECTED_KEY_PREFIX),
                !blank && apiKey.contains("${")
        );
    }

    // Package-private: lets tests bind a MockRestServiceServer to the
    // builder instead of hitting the real Brevo API.
    BrevoEmailClient(RestClient.Builder restClientBuilder, String fromAddress, String fromName) {
        this.restClient = restClientBuilder.build();
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    // Plain-text only (textContent), matching the existing CRM behavior -
    // LeadEmailService has only ever composed plain-text bodies
    // (MimeMessageHelper.setText(body, false) before this change).
    public void send(String to, String subject, String body, List<EmailAttachment> attachments) {

        Request request = new Request(
                new Sender(fromName, fromAddress),
                List.of(new Recipient(to)),
                subject,
                body,
                attachments.stream()
                        .map(a -> new Attachment(
                                Base64.getEncoder().encodeToString(a.bytes()),
                                a.filename()))
                        .toList()
        );

        try {
            restClient.post()
                    .uri(SEND_URI)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // Never log the request itself - it carries the api-key header
            // and base64 attachment content. e.getMessage() from a
            // RestClientException is Brevo's HTTP status/reason, not the
            // request body.
            log.warn("Brevo email send failed: {}", e.getMessage());
            throw new IllegalStateException("Unable to send the email. Please try again.", e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private record Request(
            Sender sender,
            List<Recipient> to,
            String subject,
            String textContent,
            List<Attachment> attachment
    ) {
    }

    private record Sender(String name, String email) {
    }

    private record Recipient(String email) {
    }

    private record Attachment(String content, String name) {
    }

}
