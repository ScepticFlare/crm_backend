package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseBackupStorageTest {

    private static final String BASE_URL = "https://project.supabase.co/storage/v1";
    private static final String BUCKET = "crm-backups";
    private static final String KEY = "crm-backup-20261111T100000Z-7.dump";

    @TempDir
    Path dir;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    private SupabaseBackupStorage storage() {
        builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer service-role-key")
                .defaultHeader("apikey", "service-role-key");
        server = MockRestServiceServer.bindTo(builder).build();
        return new SupabaseBackupStorage(builder, BUCKET);
    }

    @Test
    void upload_postsTheFileToThePrivateBucket_withoutEverOverwriting() throws IOException {

        SupabaseBackupStorage storage = storage();
        Path file = Files.write(dir.resolve("a.dump"), new byte[]{1, 2, 3});

        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-role-key"))
                .andExpect(header("x-upsert", "false"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andRespond(withSuccess());

        storage.upload(KEY, file);
        server.verify();
    }

    @Test
    void download_streamsTheObjectBack() throws IOException {

        SupabaseBackupStorage storage = storage();
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[]{9, 8, 7}, MediaType.APPLICATION_OCTET_STREAM));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        storage.download(KEY, out);

        assertThat(out.toByteArray()).containsExactly(9, 8, 7);
    }

    @Test
    void errors_carryOnlyTheStatus_neverTheResponseBodyOrUrl() throws IOException {

        SupabaseBackupStorage storage = storage();
        Path file = Files.write(dir.resolve("a.dump"), new byte[]{1});

        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"Payload too large\",\"secret\":\"service-role-key\"}"));

        assertThatThrownBy(() -> storage.upload(KEY, file))
                .isInstanceOf(IOException.class)
                .hasMessage("Backup storage rejected the upload (HTTP 400)");

        server.reset();
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("not found"));

        assertThatThrownBy(() -> storage.download(KEY, new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Backup storage returned HTTP 404");
    }

    @Test
    void delete_removesTheObject() throws IOException {

        SupabaseBackupStorage storage = storage();
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        storage.delete(KEY);
        server.verify();
    }

    @Test
    void refusesKeysThatCouldEscapeTheBucket() {

        SupabaseBackupStorage storage = storage();

        for (String bad : new String[]{"../other/x.dump", "a/b.dump", "", "x y.dump", "..dump"}) {
            assertThatThrownBy(() -> storage.delete(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void withoutUrlOrKey_itIsNotConfigured_andRefusesToWork() {

        SupabaseBackupStorage unconfigured = new SupabaseBackupStorage("", "", BUCKET, Duration.ofMinutes(1));

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.delete(KEY)).isInstanceOf(IOException.class)
                .hasMessage("Backup storage is not configured");

        assertThat(new SupabaseBackupStorage("https://p.supabase.co", "k", BUCKET, Duration.ofMinutes(1)).isConfigured()).isTrue();
    }

    // ---- retention deletes and actionable upload errors -------------------

    @Test
    void deletingAnObjectThatIsAlreadyGone_counts_asDone() throws IOException {

        SupabaseBackupStorage storage = storage();
        // plain 404, and Supabase's "HTTP 400 with a not_found body" form
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"404\",\"error\":\"not_found\",\"message\":\"Object not found\"}"));

        storage.delete(KEY);
        storage.delete(KEY);
        server.verify();
    }

    @Test
    void otherDeleteFailures_stillFail_soRetentionKeepsTheRowAndRetries() {

        SupabaseBackupStorage storage = storage();
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"error\":\"secret detail https://x.supabase.co\"}"));
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"InvalidKey\"}"));

        assertThatThrownBy(() -> storage.delete(KEY)).isInstanceOf(IOException.class)
                .hasMessage("Backup storage rejected the delete (HTTP 403)");
        assertThatThrownBy(() -> storage.delete(KEY)).isInstanceOf(IOException.class)
                .hasMessage("Backup storage rejected the delete (HTTP 400)");
    }

    @Test
    void uploadErrors_sayWhatToFix_withoutRepeatingTheResponseBody() throws IOException {

        Path file = Files.write(dir.resolve("b.dump"), new byte[]{1});

        SupabaseBackupStorage tooBig = storage();
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE).body("{\"error\":\"Payload too large\"}"));
        assertThatThrownBy(() -> tooBig.upload(KEY, file)).isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 413").hasMessageContaining("file size limit");

        // Supabase's usual form: HTTP 400, real status in the body
        SupabaseBackupStorage tooBig400 = storage();
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"statusCode\":\"413\",\"error\":\"Payload too large\",\"message\":\"The object exceeded the maximum allowed size\"}"));
        assertThatThrownBy(() -> tooBig400.upload(KEY, file)).isInstanceOf(IOException.class)
                .hasMessageStartingWith("Backup storage rejected the upload (HTTP 400). The file is larger")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("exceeded the maximum"));

        SupabaseBackupStorage noBucket = storage();
        server.expect(requestTo(BASE_URL + "/object/" + BUCKET + "/" + KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"statusCode\":\"404\",\"error\":\"Bucket not found\",\"message\":\"internal ref abc\"}"));
        assertThatThrownBy(() -> noBucket.upload(KEY, file)).isInstanceOf(IOException.class)
                .hasMessageContaining("bucket does not exist")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("internal ref"));
    }
}
