package com.compact.crm.service.mail;

import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.service.EmailAttachment;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Sends through Microsoft Graph as the signed-in user (delegated Mail.Send):
//   POST https://graph.microsoft.com/v1.0/me/sendMail   -> 202 Accepted, no body
// "from" is set to the connected address, so a token that somehow belonged to
// another mailbox is refused by Exchange (ErrorSendAsDenied) instead of
// sending as that other mailbox. Saved to the user's Sent Items.
//
// Graph accepts at most 4 MB per request, so attachments are limited to about
// 3 MB in total (base64 adds a third); a larger message is refused before
// anything is sent. (Larger attachments would need a draft + upload session.)
//
// Error classification (https://learn.microsoft.com/graph/errors, /graph/throttling):
//   401, 403 ErrorAccessDenied              -> FAILED, AUTH_REJECTED
//   429 / ErrorExceededMessageLimit         -> FAILED, THROTTLED (Retry-After honoured)
//   403 ErrorMessageSubmissionBlocked,
//   404 MailboxNotEnabledForRESTAPI etc.    -> FAILED, MAILBOX_BLOCKED
//   other 4xx                               -> FAILED
//   5xx, 408, timeout, I/O                  -> UNKNOWN (may have been sent - never resent)
@Slf4j
@Component
public class MicrosoftEmailProvider implements EmailProvider {

    static final String SEND_URI = "https://graph.microsoft.com/v1.0/me/sendMail";

    static final int MAX_ATTACHMENT_BYTES = 3 * 1024 * 1024;

    private static final Set<String> AUTH_CODES = Set.of(
            "ErrorAccessDenied", "Authorization_RequestDenied", "InvalidAuthenticationToken",
            "ErrorInvalidUser", "ErrorSendAsDenied");

    private static final Set<String> BLOCKED_CODES = Set.of(
            "ErrorMessageSubmissionBlocked", "MailboxNotEnabledForRESTAPI", "MailboxNotSupportedForRESTAPI",
            "ErrorMailboxStoreUnavailable", "ResourceNotFound", "ErrorAccountSuspend", "ErrorNonExistentMailbox");

    private static final Set<String> THROTTLE_CODES = Set.of(
            "ErrorExceededMessageLimit", "ApplicationThrottled", "ErrorTooManyObjectsOpened", "TooManyRequests",
            "ErrorExceededMessageRecipientLimit");

    private final RestClient restClient;

    @Autowired
    public MicrosoftEmailProvider() {
        this(RestClient.builder().requestFactory(OAuthSupport.timeoutFactory()));
    }

    MicrosoftEmailProvider(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public MailboxProvider provider() {
        return MailboxProvider.MICROSOFT;
    }

    @Override
    public EmailSendOutcome send(String accessToken, OutgoingEmail email) {

        long attachmentBytes = email.attachments().stream()
                .mapToLong(a -> a.bytes() == null ? 0 : a.bytes().length).sum();

        if (attachmentBytes > MAX_ATTACHMENT_BYTES) {
            return EmailSendOutcome.notSent(EmailSendOutcome.Signal.NONE,
                    "Attachments over 3 MB in total cannot be sent through Microsoft 365 from the CRM.", null);
        }

        try {

            restClient.post()
                    .uri(SEND_URI)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request(email))
                    .retrieve()
                    .toBodilessEntity();

            return EmailSendOutcome.accepted();

        } catch (RestClientResponseException e) {

            return classify(e);

        } catch (RestClientException e) {

            log.warn("Microsoft Graph send outcome unknown: {}", e.getClass().getSimpleName());

            return new EmailSendOutcome(EmailSendOutcome.Result.UNKNOWN, null,
                    "No usable response from Microsoft 365 (" + e.getClass().getSimpleName() + ")");
        }
    }

    static SendMailRequest request(OutgoingEmail email) {

        List<Attachment> attachments = email.attachments().stream()
                .map(MicrosoftEmailProvider::attachment)
                .toList();

        List<Header> headers = email.sendKey() == null ? List.of()
                : List.of(new Header("X-CRM-Send-Id", MimeMessageBuilder.sendId(email.sendKey())));

        Message message = new Message(
                MimeMessageBuilder.clean(email.subject()),
                new Body("Text", email.body() == null ? "" : email.body()),
                new Recipient(new Address(MimeMessageBuilder.clean(email.fromAddress()),
                        email.fromName() == null || email.fromName().isBlank() ? null : MimeMessageBuilder.clean(email.fromName()))),
                List.of(new Recipient(new Address(MimeMessageBuilder.clean(email.to()), null))),
                attachments.isEmpty() ? null : attachments,
                headers.isEmpty() ? null : headers);

        return new SendMailRequest(message, true);
    }

    private static Attachment attachment(EmailAttachment a) {
        String name = a.filename() == null || a.filename().isBlank() ? "attachment" : MimeMessageBuilder.clean(a.filename());
        return new Attachment("#microsoft.graph.fileAttachment", name, MimeMessageBuilder.contentTypeOf(name),
                Base64.getEncoder().encodeToString(a.bytes() == null ? new byte[0] : a.bytes()));
    }

    static EmailSendOutcome classify(RestClientResponseException e) {

        int status = e.getStatusCode().value();
        String[] error = parse(e.getResponseBodyAsString());
        String code = error[0];
        String detail = OAuthSupport.truncate("Microsoft 365 HTTP " + status
                + (code == null ? "" : " " + code) + (error[1] == null ? "" : ": " + error[1]), 300);
        Integer retryAfter = OAuthSupport.retryAfterSeconds(e.getResponseHeaders());

        log.warn("Microsoft Graph send refused: HTTP {} code={}", status, code);

        if (status == 408 || e.getStatusCode().is5xxServerError()) {
            return new EmailSendOutcome(EmailSendOutcome.Result.UNKNOWN, status, detail);
        }

        if (status == 429 || THROTTLE_CODES.contains(code)) {
            boolean daily = "ErrorExceededMessageLimit".equals(code) || "ErrorExceededMessageRecipientLimit".equals(code);
            return signal(status, daily ? "Microsoft 365 sending limit reached for this mailbox" : detail,
                    EmailSendOutcome.Signal.THROTTLED, daily ? null : (retryAfter != null ? retryAfter : 60));
        }

        if (BLOCKED_CODES.contains(code)) {
            return signal(status, detail, EmailSendOutcome.Signal.MAILBOX_BLOCKED, null);
        }

        if (status == 401 || (status == 403 && (code == null || AUTH_CODES.contains(code)))) {
            return signal(status, detail, EmailSendOutcome.Signal.AUTH_REJECTED, null);
        }

        return new EmailSendOutcome(EmailSendOutcome.Result.FAILED, status, detail);
    }

    private static EmailSendOutcome signal(int status, String detail, EmailSendOutcome.Signal signal, Integer retryAfter) {
        return new EmailSendOutcome(EmailSendOutcome.Result.FAILED, status, detail, signal, retryAfter, true);
    }

    // {"error":{"code":"ErrorAccessDenied","message":"..."}}
    @SuppressWarnings("unchecked")
    private static String[] parse(String body) {
        try {
            Map<String, Object> root = OAuthSupport.JSON.readValue(body, new TypeReference<Map<String, Object>>() { });
            Map<String, Object> error = root.get("error") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            return new String[]{
                    error.get("code") instanceof String c ? c : null,
                    error.get("message") instanceof String s ? s : null};
        } catch (Exception ex) {
            return new String[]{null, null};
        }
    }

    // ---- Graph request shape ------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendMailRequest(Message message, boolean saveToSentItems) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Message(String subject, Body body, Recipient from, List<Recipient> toRecipients,
                   List<Attachment> attachments, List<Header> internetMessageHeaders) {
    }

    record Body(String contentType, String content) {
    }

    record Recipient(Address emailAddress) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Address(String address, String name) {
    }

    record Attachment(@JsonProperty("@odata.type") String odataType, String name, String contentType,
                      String contentBytes) {
    }

    record Header(String name, String value) {
    }
}
