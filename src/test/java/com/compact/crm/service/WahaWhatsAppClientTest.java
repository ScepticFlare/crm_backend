package com.compact.crm.service;

import com.compact.crm.exception.IntegrationNotConfiguredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Request construction and outcome classification against a mock WAHA server
 * (MockRestServiceServer) - no network, no real WhatsApp.
 */
class WahaWhatsAppClientTest {

    private static final String BASE = "http://waha.test";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void init() {
        builder = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("X-Api-Key", "test-waha-key")
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private WahaWhatsAppClient client() {
        return new WahaWhatsAppClient(builder, "crm", true, Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void sendText_postsTheDocumentedPayloadWithTheApiKeyHeader_andReadsAStringId() {

        server.expect(requestTo(BASE + "/api/sendText"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Api-Key", "test-waha-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.session").value("crm"))
                .andExpect(jsonPath("$.chatId").value("919876543210@c.us"))
                .andExpect(jsonPath("$.text").value("Hi Rahul"))
                .andExpect(jsonPath("$.linkPreview").value(false))
                .andRespond(withSuccess("{\"id\":\"true_919876543210@c.us_3EB0ABC\",\"timestamp\":1730714400}",
                        MediaType.APPLICATION_JSON));

        WhatsAppClient.SendOutcome outcome = client().sendText("919876543210", "Hi Rahul");

        assertThat(outcome.result()).isEqualTo(WhatsAppClient.Result.ACCEPTED);
        assertThat(outcome.providerMessageId()).isEqualTo("true_919876543210@c.us_3EB0ABC");
        assertThat(outcome.pauseSending()).isFalse();
        server.verify();
    }

    @Test
    void sendText_readsAnObjectIdWithSerialized_engineDifferences() {

        server.expect(requestTo(BASE + "/api/sendText"))
                .andRespond(withSuccess("{\"id\":{\"fromMe\":true,\"_serialized\":\"true_919876543210@c.us_XYZ\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client().sendText("919876543210", "x").providerMessageId()).isEqualTo("true_919876543210@c.us_XYZ");
    }

    @Test
    void sendText_withoutAnIdIsStillAccepted_butCannotBeTracked() {

        server.expect(requestTo(BASE + "/api/sendText")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        WhatsAppClient.SendOutcome outcome = client().sendText("919876543210", "x");

        assertThat(outcome.result()).isEqualTo(WhatsAppClient.Result.ACCEPTED);
        assertThat(outcome.providerMessageId()).isNull();
        assertThat(outcome.detail()).contains("cannot be tracked");
    }

    @Test
    void a4xxIsADefiniteFailure_andProviderLevelOnesPauseTheBatch() {

        server.expect(requestTo(BASE + "/api/sendText"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"chatId 919876543210@c.us is invalid\"}"));

        WhatsAppClient.SendOutcome bad = client().sendText("919876543210", "x");

        assertThat(bad.result()).isEqualTo(WhatsAppClient.Result.FAILED);
        assertThat(bad.pauseSending()).isFalse();
        // the provider's message is kept, the phone number in it is not
        assertThat(bad.detail()).contains("HTTP 400").contains("is invalid").doesNotContain("9876543210");

        for (HttpStatus status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.UNPROCESSABLE_ENTITY,
                HttpStatus.TOO_MANY_REQUESTS}) {

            init();
            server.expect(requestTo(BASE + "/api/sendText")).andRespond(withStatus(status));

            WhatsAppClient.SendOutcome outcome = client().sendText("919876543210", "x");
            assertThat(outcome.result()).as(status.toString()).isEqualTo(WhatsAppClient.Result.FAILED);
            assertThat(outcome.pauseSending()).as(status.toString()).isTrue();
        }
    }

    @Test
    void a5xx408OrTimeoutIsUnknown_andPauses_neverRetried() {

        server.expect(requestTo(BASE + "/api/sendText")).andRespond(withServerError());

        WhatsAppClient.SendOutcome err = client().sendText("919876543210", "x");
        assertThat(err.result()).isEqualTo(WhatsAppClient.Result.UNKNOWN);
        assertThat(err.pauseSending()).isTrue();
        server.verify(); // exactly one request: no retry

        init();
        server.expect(requestTo(BASE + "/api/sendText")).andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
        assertThat(client().sendText("919876543210", "x").result()).isEqualTo(WhatsAppClient.Result.UNKNOWN);

        init();
        server.expect(requestTo(BASE + "/api/sendText")).andRespond(request -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"));
        });
        WhatsAppClient.SendOutcome timeout = client().sendText("919876543210", "x");
        assertThat(timeout.result()).isEqualTo(WhatsAppClient.Result.UNKNOWN);
        assertThat(timeout.detail()).doesNotContain("Hi");
        server.verify();
    }

    @Test
    void anInvalidDestinationIsRefusedWithoutCallingTheProvider() {
        assertThat(client().sendText("12345", "x").result()).isEqualTo(WhatsAppClient.Result.FAILED);
        assertThat(client().sendText(null, "x").result()).isEqualTo(WhatsAppClient.Result.FAILED);
        server.verify();
    }

    @Test
    void sessionStatus_isReadFromWaha_cached_andReadyOnlyWhenWorking() {

        server.expect(requestTo(BASE + "/api/sessions/crm"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Api-Key", "test-waha-key"))
                .andRespond(withSuccess("{\"name\":\"crm\",\"status\":\"SCAN_QR_CODE\"}", MediaType.APPLICATION_JSON));

        WahaWhatsAppClient c = client();

        assertThat(c.sessionStatus()).isEqualTo("SCAN_QR_CODE");
        assertThat(c.isReady()).isFalse(); // served from the cache: still one request
        server.verify();

        assertThatThrownBy(c::requireReady)
                .isInstanceOf(IntegrationNotConfiguredException.class)
                .hasMessageContaining("SCAN_QR_CODE");
    }

    @Test
    void sessionStatus_mapsUnreachableAndMissingSessions() {

        server.expect(requestTo(BASE + "/api/sessions/crm")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client().sessionStatus()).isEqualTo("SESSION_NOT_FOUND");

        init();
        server.expect(requestTo(BASE + "/api/sessions/crm")).andRespond(withServerError());
        assertThat(client().sessionStatus()).isEqualTo("UNREACHABLE");

        init();
        server.expect(requestTo(BASE + "/api/sessions/crm")).andRespond(withSuccess("{\"status\":\"WORKING\"}",
                MediaType.APPLICATION_JSON));
        assertThat(client().isReady()).isTrue();
    }

    @Test
    void notConfigured_refusesEverything_withoutAnyRequest() {

        WahaWhatsAppClient c = new WahaWhatsAppClient(builder, "default", false, Clock.systemUTC());

        assertThat(c.isConfigured()).isFalse();
        assertThat(c.sessionStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(c.isReady()).isFalse();
        assertThatThrownBy(c::requireConfigured).isInstanceOf(IntegrationNotConfiguredException.class);
        assertThat(c.sendText("919876543210", "x").result()).isEqualTo(WhatsAppClient.Result.FAILED);
        server.verify();
    }

    @Test
    void springConstructor_withoutUrlOrKey_isNotConfigured_andTheCrmStillStarts() {

        assertThat(new WahaWhatsAppClient(true, "", "", "default").isConfigured()).isFalse();
        assertThat(new WahaWhatsAppClient(true, "http://waha:3000", "", "default").isConfigured()).isFalse();
        assertThat(new WahaWhatsAppClient(false, "http://waha:3000", "k", "default").isConfigured()).isFalse();
        assertThat(new WahaWhatsAppClient(true, "http://waha:3000", "k", "default").isConfigured()).isTrue();
    }
}
