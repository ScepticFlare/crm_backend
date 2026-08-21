package com.compact.crm.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BrevoEmailClientTest {

    private static final String BASE_URL = "https://api.brevo.com/v3";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    private void init() {
        builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("api-key", "test-brevo-key")
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private BrevoEmailClient client() {
        return new BrevoEmailClient(builder, "sales@compact.example", "Compact Systems");
    }

    @Test
    void send_postsToBrevoSmtpEmailEndpoint_withApiKeyAndSenderRecipientSubjectBody() {

        init();

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-brevo-key"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.sender.email").value("sales@compact.example"))
                .andExpect(jsonPath("$.sender.name").value("Compact Systems"))
                .andExpect(jsonPath("$.to[0].email").value("rajesh@acme.com"))
                .andExpect(jsonPath("$.subject").value("Product Brochure"))
                .andExpect(jsonPath("$.textContent").value("Please find attached..."))
                .andRespond(withSuccess("{\"messageId\":\"abc123\"}", MediaType.APPLICATION_JSON));

        client().send("rajesh@acme.com", "Product Brochure", "Please find attached...", List.of());

        server.verify();
    }

    @Test
    void send_withAttachment_sendsBase64ContentAndFilename() {

        init();

        byte[] fileBytes = "hello world".getBytes();
        String expectedBase64 = Base64.getEncoder().encodeToString(fileBytes);

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.attachment[0].name").value("brochure.pdf"))
                .andExpect(jsonPath("$.attachment[0].content").value(expectedBase64))
                .andRespond(withSuccess());

        client().send(
                "rajesh@acme.com", "Subject", "Body",
                List.of(new EmailAttachment("brochure.pdf", fileBytes)));

        server.verify();
    }

    @Test
    void send_withMultipleAttachments_includesEachOne() {

        init();

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andExpect(jsonPath("$.attachment[0].name").value("brochure.pdf"))
                .andExpect(jsonPath("$.attachment[1].name").value("company-profile.pdf"))
                .andRespond(withSuccess());

        client().send(
                "rajesh@acme.com", "Subject", "Body",
                List.of(
                        new EmailAttachment("brochure.pdf", "a".getBytes()),
                        new EmailAttachment("company-profile.pdf", "b".getBytes())
                ));

        server.verify();
    }

    @Test
    void send_noAttachments_omitsTheAttachmentField() {

        init();

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andExpect(jsonPath("$.attachment").doesNotExist())
                .andRespond(withSuccess());

        client().send("rajesh@acme.com", "Subject", "Body", List.of());

        server.verify();
    }

    @Test
    void send_brevoReturnsError_translatesToIllegalStateException_notRawException() {

        init();

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"invalid api-key\"}"));

        assertThatThrownBy(() -> client().send("rajesh@acme.com", "Subject", "Body", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to send the email. Please try again.");

        server.verify();
    }

}
