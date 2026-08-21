package com.compact.crm.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseStorageDocumentStorageServiceTest {

    private static final String BASE_URL = "https://project.supabase.co/storage/v1";
    private static final String BUCKET = "documents";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    private void init() {
        builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer service-role-key")
                .defaultHeader("apikey", "service-role-key");
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private DocumentStorageService storage() {
        return new SupabaseStorageDocumentStorageService(builder, BUCKET);
    }

    @Test
    void store_uploadsBytesAndReturnsAGeneratedKey_neverTheOriginalFilename() throws IOException {

        init();

        server.expect(requestTo(startsWith(BASE_URL + "/object/" + BUCKET + "/")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-role-key"))
                .andExpect(header("apikey", "service-role-key"))
                .andRespond(withSuccess());

        DocumentStorageService storage = storage();

        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/brochure.pdf", "application/pdf", "hello world".getBytes());

        String storedFileName = storage.store(file);

        assertThat(storedFileName).endsWith(".pdf").doesNotContain("..", "/", "\\");
        server.verify();
    }

    @Test
    void store_thenLoad_roundTripsTheSameBytes() throws IOException {

        init();

        server.expect(requestTo(startsWith(BASE_URL + "/object/" + BUCKET + "/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        server.expect(requestTo(startsWith(BASE_URL + "/object/" + BUCKET + "/")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("hello world", MediaType.APPLICATION_PDF));

        DocumentStorageService storage = storage();

        MockMultipartFile file = new MockMultipartFile(
                "file", "brochure.pdf", "application/pdf", "hello world".getBytes());
        String storedFileName = storage.store(file);

        assertThat(storage.load(storedFileName)).isEqualTo("hello world".getBytes());
        server.verify();
    }

    @Test
    void load_missingObject_throwsIOException() {

        init();

        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/missing.pdf"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        DocumentStorageService storage = storage();

        assertThatThrownBy(() -> storage.load("missing.pdf")).isInstanceOf(IOException.class);
        server.verify();
    }

    @Test
    void delete_sendsADeleteRequestForTheStorageKey() throws IOException {

        init();

        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/brochure.pdf"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        DocumentStorageService storage = storage();
        storage.delete("brochure.pdf");

        server.verify();
    }

}
