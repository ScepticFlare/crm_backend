package com.compact.crm.service.mail;

import com.compact.crm.service.EmailAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** Microsoft Graph sendMail against a mocked HTTP server - no real Microsoft call, no real email. */
class MicrosoftEmailProviderTest {

    private MockRestServiceServer server;
    private MicrosoftEmailProvider provider;

    private final OutgoingEmail email = new OutgoingEmail("ravi@company.test", "Ravi", "lead@customer.test",
            "Hello Acme", "Dear Rajesh,\nRegards", List.of(), "batch:LEAD:1");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new MicrosoftEmailProvider(builder);
    }

    @Test
    void buildsAGraphSendMailRequest_fromTheConnectedMailbox() {

        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer graph-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.saveToSentItems").value(true))
                .andExpect(jsonPath("$.message.subject").value("Hello Acme"))
                .andExpect(jsonPath("$.message.body.contentType").value("Text"))
                .andExpect(jsonPath("$.message.body.content").value("Dear Rajesh,\nRegards"))
                .andExpect(jsonPath("$.message.from.emailAddress.address").value("ravi@company.test"))
                .andExpect(jsonPath("$.message.toRecipients.length()").value(1))
                .andExpect(jsonPath("$.message.toRecipients[0].emailAddress.address").value("lead@customer.test"))
                .andExpect(jsonPath("$.message.internetMessageHeaders[0].name").value("X-CRM-Send-Id"))
                .andExpect(jsonPath("$.message.internetMessageHeaders[0].value").value(MimeMessageBuilder.sendId("batch:LEAD:1")))
                .andExpect(jsonPath("$.message.attachments").doesNotExist())
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(provider.send("graph-token", email).result()).isEqualTo(EmailSendOutcome.Result.ACCEPTED);
        server.verify();
    }

    @Test
    void attachmentsAreFileAttachments() {

        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI))
                .andExpect(jsonPath("$.message.attachments[0]['@odata.type']").value("#microsoft.graph.fileAttachment"))
                .andExpect(jsonPath("$.message.attachments[0].name").value("brochure.pdf"))
                .andExpect(jsonPath("$.message.attachments[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.message.attachments[0].contentBytes").value("AQID"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        provider.send("t", new OutgoingEmail("a@c.test", null, "b@c.test", "S", "B",
                List.of(new EmailAttachment("brochure.pdf", new byte[]{1, 2, 3})), null));

        server.verify();
    }

    @Test
    void attachmentsOverTheGraphLimit_areRefusedWithoutCallingGraph() {

        EmailSendOutcome outcome = provider.send("t", new OutgoingEmail("a@c.test", null, "b@c.test", "S", "B",
                List.of(new EmailAttachment("big.pdf", new byte[MicrosoftEmailProvider.MAX_ATTACHMENT_BYTES + 1])), null));

        assertThat(outcome.result()).isEqualTo(EmailSendOutcome.Result.FAILED);
        assertThat(outcome.providerCalled()).isFalse();
        server.verify();   // no request was made
    }

    @Test
    void throttling_honoursRetryAfter() {

        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"ApplicationThrottled\",\"message\":\"Too many requests\"}}"));

        EmailSendOutcome outcome = provider.send("t", email);

        assertThat(outcome.signal()).isEqualTo(EmailSendOutcome.Signal.THROTTLED);
        assertThat(outcome.retryAfterSeconds()).isEqualTo(42);
        assertThat(outcome.result()).isEqualTo(EmailSendOutcome.Result.FAILED);
    }

    @Test
    void aBlockedOrUnlicensedMailbox_isMailboxBlocked() {

        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"ErrorMessageSubmissionBlocked\",\"message\":\"blocked\"}}"));
        assertThat(provider.send("t", email).signal()).isEqualTo(EmailSendOutcome.Signal.MAILBOX_BLOCKED);

        server.reset();
        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"MailboxNotEnabledForRESTAPI\",\"message\":\"no mailbox\"}}"));
        assertThat(provider.send("t", email).signal()).isEqualTo(EmailSendOutcome.Signal.MAILBOX_BLOCKED);
    }

    @Test
    void anExpiredTokenOrDeniedSendAs_isAuthRejected() {

        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"InvalidAuthenticationToken\",\"message\":\"expired\"}}"));
        assertThat(provider.send("t", email).signal()).isEqualTo(EmailSendOutcome.Signal.AUTH_REJECTED);

        server.reset();
        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"ErrorSendAsDenied\",\"message\":\"not allowed\"}}"));
        assertThat(provider.send("t", email).signal()).isEqualTo(EmailSendOutcome.Signal.AUTH_REJECTED);
    }

    @Test
    void serverErrorsAndTimeouts_areUnknown() {

        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        assertThat(provider.send("t", email).result()).isEqualTo(EmailSendOutcome.Result.UNKNOWN);

        server.reset();
        server.expect(requestTo(MicrosoftEmailProvider.SEND_URI)).andRespond(withException(new SocketTimeoutException()));
        assertThat(provider.send("t", email).result()).isEqualTo(EmailSendOutcome.Result.UNKNOWN);
    }
}
