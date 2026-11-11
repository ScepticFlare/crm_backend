package com.compact.crm.service.mail;

import com.compact.crm.enums.MailboxProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Set;

// Sends through the Gmail API as the signed-in mailbox owner:
//   POST https://gmail.googleapis.com/upload/gmail/v1/users/me/messages/send?uploadType=media
//   Content-Type: message/rfc822, body = the raw MIME message (MimeMessageBuilder)
// The media-upload form is used because it accepts messages up to 35 MB (the
// metadata-only form is much smaller), so attachments work. Needs only the
// gmail.send scope. The From header is the connected address; Gmail sends
// from the authenticated account regardless, so the CRM can never send as
// anyone else.
//
// Error classification (https://developers.google.com/gmail/api/guides/handle-errors):
//   401                                   -> FAILED, AUTH_REJECTED (refresh + retry once, upstream)
//   403 insufficientPermissions           -> FAILED, AUTH_REJECTED (gmail.send not granted)
//   403/429 rate/daily/quota limits       -> FAILED, THROTTLED (never retried in a loop)
//   400 failedPrecondition                -> FAILED, MAILBOX_BLOCKED (Gmail not enabled for the account)
//   other 4xx (bad recipient, too large)  -> FAILED
//   5xx, 408, timeout, I/O                -> UNKNOWN (may have been sent - never resent)
@Slf4j
@Component
public class GmailEmailProvider implements EmailProvider {

    static final String SEND_URI =
            "https://gmail.googleapis.com/upload/gmail/v1/users/me/messages/send?uploadType=media";

    // Gmail's limit for a whole message (after encoding).
    static final int MAX_MESSAGE_BYTES = 35 * 1024 * 1024;

    private static final Set<String> THROTTLE_REASONS = Set.of(
            "rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "quotaExceeded",
            "concurrentLimitExceeded");

    private static final Set<String> DAILY_REASONS = Set.of("dailyLimitExceeded", "quotaExceeded");

    private final RestClient restClient;

    @Autowired
    public GmailEmailProvider() {
        this(RestClient.builder().requestFactory(OAuthSupport.timeoutFactory()));
    }

    GmailEmailProvider(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public MailboxProvider provider() {
        return MailboxProvider.GOOGLE;
    }

    @Override
    public EmailSendOutcome send(String accessToken, OutgoingEmail email) {

        byte[] mime = MimeMessageBuilder.build(email);

        if (mime.length > MAX_MESSAGE_BYTES) {
            return EmailSendOutcome.notSent(EmailSendOutcome.Signal.NONE,
                    "The message with its attachments is larger than Gmail's 35 MB limit.", null);
        }

        try {

            restClient.post()
                    .uri(SEND_URI)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.parseMediaType("message/rfc822"))
                    .accept(MediaType.APPLICATION_JSON)
                    .body(mime)
                    .retrieve()
                    .toBodilessEntity();

            return EmailSendOutcome.accepted();

        } catch (RestClientResponseException e) {

            return classify(e);

        } catch (RestClientException e) {

            log.warn("Gmail send outcome unknown: {}", e.getClass().getSimpleName());

            return new EmailSendOutcome(EmailSendOutcome.Result.UNKNOWN, null,
                    "No usable response from Gmail (" + e.getClass().getSimpleName() + ")");
        }
    }

    static EmailSendOutcome classify(RestClientResponseException e) {

        int status = e.getStatusCode().value();
        Error error = parse(e.getResponseBodyAsString());
        String detail = OAuthSupport.truncate("Gmail HTTP " + status
                + (error.message == null ? "" : ": " + error.message), 300);
        Integer retryAfter = OAuthSupport.retryAfterSeconds(e.getResponseHeaders());

        log.warn("Gmail send refused: HTTP {} reason={}", status, error.reason);

        if (status == 408 || e.getStatusCode().is5xxServerError()) {
            return new EmailSendOutcome(EmailSendOutcome.Result.UNKNOWN, status, detail);
        }

        if (status == 401) {
            return signal(status, detail, EmailSendOutcome.Signal.AUTH_REJECTED, null);
        }

        if (status == 429 || THROTTLE_REASONS.contains(error.reason)) {
            boolean daily = DAILY_REASONS.contains(error.reason);
            return signal(status, daily ? "Gmail daily sending limit reached for this mailbox" : detail,
                    EmailSendOutcome.Signal.THROTTLED, daily ? null : (retryAfter != null ? retryAfter : 60));
        }

        if (status == 403 && ("insufficientPermissions".equals(error.reason)
                || "ACCESS_TOKEN_SCOPE_INSUFFICIENT".equals(error.reason)
                || "PERMISSION_DENIED".equals(error.status) && error.message != null
                && error.message.toLowerCase().contains("scope"))) {
            return signal(status, detail, EmailSendOutcome.Signal.AUTH_REJECTED, null);
        }

        if (status == 400 && "failedPrecondition".equals(error.reason)) {
            return signal(status, detail, EmailSendOutcome.Signal.MAILBOX_BLOCKED, null);
        }

        return new EmailSendOutcome(EmailSendOutcome.Result.FAILED, status, detail);
    }

    private static EmailSendOutcome signal(int status, String detail, EmailSendOutcome.Signal signal, Integer retryAfter) {
        return new EmailSendOutcome(EmailSendOutcome.Result.FAILED, status, detail, signal, retryAfter, true);
    }

    private record Error(String message, String reason, String status) {
    }

    // {"error":{"code":403,"message":"...","status":"PERMISSION_DENIED","errors":[{"reason":"..."}]}}
    @SuppressWarnings("unchecked")
    private static Error parse(String body) {

        try {

            Map<String, Object> root = OAuthSupport.JSON.readValue(body, new TypeReference<Map<String, Object>>() { });
            Map<String, Object> error = root.get("error") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

            String reason = null;

            if (error.get("errors") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                reason = first.get("reason") instanceof String r ? r : null;
            }

            if (reason == null && error.get("details") instanceof List<?> details) {
                for (Object d : details) {
                    if (d instanceof Map<?, ?> dm && dm.get("reason") instanceof String r) {
                        reason = r;
                        break;
                    }
                }
            }

            return new Error(
                    error.get("message") instanceof String s ? s : null,
                    reason,
                    error.get("status") instanceof String st ? st : null);

        } catch (Exception ex) {
            return new Error(null, null, null);
        }
    }
}
