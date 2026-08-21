package com.compact.crm.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
    private final boolean checkAccountOnStartup;

    // Explicitly @Autowired: a second (package-private, test-only)
    // constructor also exists below, and Spring's implicit
    // single-constructor injection only applies when there's exactly one
    // non-default constructor - without this marker bean creation fails at
    // startup with "No default constructor found".
    @Autowired
    public BrevoEmailClient(
            @Value("${brevo.api-key}") String apiKey,
            @Value("${crm.mail.from-address}") String fromAddress,
            @Value("${crm.mail.from-name}") String fromName,
            @Value("${crm.debug.brevo-account-check-on-startup:false}") boolean checkAccountOnStartup
    ) {
        this(RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", sanitizeApiKey(apiKey))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE),
                fromAddress, fromName, checkAccountOnStartup);

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
    // builder instead of hitting the real Brevo API. checkAccountOnStartup
    // is irrelevant here since @PostConstruct never fires for a bean built
    // via plain `new` (only Spring's container invokes it), but the field
    // still needs a value.
    BrevoEmailClient(RestClient.Builder restClientBuilder, String fromAddress, String fromName) {
        this(restClientBuilder, fromAddress, fromName, false);
    }

    BrevoEmailClient(
            RestClient.Builder restClientBuilder, String fromAddress, String fromName,
            boolean checkAccountOnStartup
    ) {
        this.restClient = restClientBuilder.build();
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.checkAccountOnStartup = checkAccountOnStartup;
    }

    // TEMPORARY diagnostic for the "401 Key not found" investigation - see
    // logApiKeyDiagnostics above. Calls GET /v3/account with the exact same
    // api-key header used by send() below, to tell apart two possible
    // causes of that 401: (A) Brevo rejects this key outright, in which
    // case this call also 401s, vs (B) the key is fine and something about
    // the /smtp/email request specifically is the problem, in which case
    // this call succeeds. Only runs when
    // crm.debug.brevo-account-check-on-startup=true (on by default in
    // application.properties, forced off in tests via
    // src/test/resources/application.properties so `mvn test` never makes
    // a real network call) and never touches send()/the email-sending path
    // itself. Logs only the HTTP status and success/failure - never the key,
    // never the response body. Remove this method, its call site in the
    // constructor above, the checkAccountOnStartup field/parameters, and
    // the crm.debug.brevo-account-check-on-startup property once the
    // investigation is done.
    @PostConstruct
    void verifyApiKeyAgainstAccountEndpoint() {

        if (!checkAccountOnStartup) {
            return;
        }

        try {

            restClient.get()
                    .uri("/account")
                    .retrieve()
                    .toBodilessEntity();

            log.info("Brevo GET /v3/account diagnostic: succeeded (status=200) - the api-key is valid; " +
                    "if /smtp/email still 401s, the key itself is not the problem.");

        } catch (RestClientResponseException e) {

            HttpStatusCode status = e.getStatusCode();
            log.warn("Brevo GET /v3/account diagnostic: failed, status={} - the api-key itself is being " +
                    "rejected by Brevo (same root cause as the /smtp/email 401).", status);

        } catch (RestClientException e) {
            log.warn("Brevo GET /v3/account diagnostic: request could not complete ({}).",
                    e.getClass().getSimpleName());
        }
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
