package com.compact.crm.service.mail;

import com.compact.crm.service.EmailAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Gmail API sending against a mocked HTTP server - no real Google call, no real email. */
class GmailEmailProviderTest {

    private MockRestServiceServer server;
    private GmailEmailProvider provider;

    private final OutgoingEmail email = new OutgoingEmail("asha@company.test", "Asha", "lead@customer.test",
            "Hello Acme", "Dear Rajesh", List.of(), "batch:LEAD:1");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new GmailEmailProvider(builder);
    }

    @Test
    void sendsTheRawMessage_toTheGmailUploadEndpoint_asTheConnectedMailbox() {

        server.expect(requestTo(GmailEmailProvider.SEND_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-123"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, "message/rfc822"))
                .andExpect(request -> {
                    String mime = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.US_ASCII);
                    assertThat(mime).contains("From: \"Asha\" <asha@company.test>");
                    assertThat(mime).contains("To: <lead@customer.test>");
                    assertThat(mime).contains("Subject: Hello Acme");
                })
                .andRespond(withSuccess("{\"id\":\"18c\",\"threadId\":\"18c\"}", MediaType.APPLICATION_JSON));

        EmailSendOutcome outcome = provider.send("access-123", email);

        assertThat(outcome.result()).isEqualTo(EmailSendOutcome.Result.ACCEPTED);
        server.verify();
    }

    @Test
    void attachmentsTravelInsideTheMimeMessage() {

        server.expect(requestTo(GmailEmailProvider.SEND_URI))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.US_ASCII))
                        .contains("multipart/mixed").contains("filename=\"brochure.pdf\""))
                .andRespond(withSuccess());

        provider.send("t", new OutgoingEmail("a@c.test", null, "b@c.test", "S", "B",
                List.of(new EmailAttachment("brochure.pdf", new byte[]{9})), null));

        server.verify();
    }

    @Test
    void unauthorized_isAuthRejected_andDefinitelyNotSent() {

        server.expect(requestTo(GmailEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\",\"status\":\"UNAUTHENTICATED\"}}"));

        EmailSendOutcome outcome = provider.send("t", email);

        assertThat(outcome.result()).isEqualTo(EmailSendOutcome.Result.FAILED);
        assertThat(outcome.signal()).isEqualTo(EmailSendOutcome.Signal.AUTH_REJECTED);
        assertThat(outcome.httpStatus()).isEqualTo(401);
    }

    @Test
    void missingSendScope_isAuthRejected() {

        server.expect(requestTo(GmailEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":403,\"message\":\"Request had insufficient authentication scopes.\","
                                + "\"errors\":[{\"reason\":\"insufficientPermissions\"}],\"status\":\"PERMISSION_DENIED\"}}"));

        assertThat(provider.send("t", email).signal()).isEqualTo(EmailSendOutcome.Signal.AUTH_REJECTED);
    }

    @Test
    void rateLimiting_isThrottled_withTheProvidersRetryAfter() {

        server.expect(requestTo(GmailEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "17")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":429,\"message\":\"User-rate limit exceeded\","
                                + "\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}"));

        EmailSendOutcome outcome = provider.send("t", email);

        assertThat(outcome.result()).isEqualTo(EmailSendOutcome.Result.FAILED);
        assertThat(outcome.signal()).isEqualTo(EmailSendOutcome.Signal.THROTTLED);
        assertThat(outcome.retryAfterSeconds()).isEqualTo(17);
    }

    @Test
    void theDailySendingLimit_isThrottledWithoutAShortRetry() {

        server.expect(requestTo(GmailEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":403,\"message\":\"Daily Limit Exceeded\","
                                + "\"errors\":[{\"reason\":\"dailyLimitExceeded\"}]}}"));

        EmailSendOutcome outcome = provider.send("t", email);

        assertThat(outcome.signal()).isEqualTo(EmailSendOutcome.Signal.THROTTLED);
        assertThat(outcome.retryAfterSeconds()).isNull();
    }

    @Test
    void aRejectedRecipient_isFailed_withASanitizedDetail() {

        server.expect(requestTo(GmailEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":400,\"message\":\"Invalid To header\",\"errors\":[{\"reason\":\"invalidArgument\"}]}}"));

        EmailSendOutcome outcome = provider.send("access-secret", email);

        assertThat(outcome.result()).isEqualTo(EmailSendOutcome.Result.FAILED);
        assertThat(outcome.signal()).isEqualTo(EmailSendOutcome.Signal.NONE);
        assertThat(outcome.detail()).isEqualTo("Gmail HTTP 400: Invalid To header").doesNotContain("access-secret");
    }

    @Test
    void serverErrorsAndTimeouts_areUnknown_neverFailed() {

        server.expect(requestTo(GmailEmailProvider.SEND_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(provider.send("t", email).result()).isEqualTo(EmailSendOutcome.Result.UNKNOWN);

        server.reset();
        server.expect(requestTo(GmailEmailProvider.SEND_URI)).andRespond(withException(new SocketTimeoutException("read")));
        assertThat(provider.send("t", email).result()).isEqualTo(EmailSendOutcome.Result.UNKNOWN);
    }
}
