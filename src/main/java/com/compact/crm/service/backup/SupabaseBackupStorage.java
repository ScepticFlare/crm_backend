package com.compact.crm.service.backup;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

// Backup files in a PRIVATE Supabase Storage bucket, over the same plain HTTP
// Storage API (and the same SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY) as
// SupabaseStorageDocumentStorageService. The service-role key stays on the
// backend; files are uploaded with x-upsert: false (an existing backup is never
// overwritten) and are only ever read back through download(). Uploads and
// downloads are streamed, never held in memory. Supabase encrypts stored
// objects at rest and every hop is HTTPS.
//
// Exceptions carry only the HTTP status, never the response body or URL.
public class SupabaseBackupStorage implements BackupStorage {

    // flat keys only: no "/" (the URI template would encode it) and no traversal
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,180}");

    private final RestClient restClient;
    private final String bucket;

    public SupabaseBackupStorage(String supabaseUrl, String serviceRoleKey, String bucket, Duration timeout) {
        this(configured(supabaseUrl, serviceRoleKey)
                ? RestClient.builder()
                        .requestFactory(requestFactory(timeout))
                        .baseUrl(supabaseUrl.trim().replaceAll("/+$", "") + "/storage/v1")
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceRoleKey.trim())
                        .defaultHeader("apikey", serviceRoleKey.trim())
                : null, bucket);
    }

    // Package-private: lets tests bind a MockRestServiceServer to the builder.
    SupabaseBackupStorage(RestClient.Builder builder, String bucket) {
        this.restClient = builder == null ? null : builder.build();
        this.bucket = bucket;
    }

    private static boolean configured(String url, String key) {
        return url != null && !url.isBlank() && key != null && !key.isBlank();
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
        factory.setReadTimeout(timeout);
        return factory;
    }

    @Override
    public boolean isConfigured() {
        return restClient != null && bucket != null && !bucket.isBlank();
    }

    @Override
    public void upload(String key, Path file) throws IOException {
        requireConfigured();
        try {
            restClient.post()
                    .uri("/object/{bucket}/{key}", bucket, checkKey(key))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("x-upsert", "false")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new FileSystemResource(file))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new IOException("Backup storage rejected the upload (HTTP " + e.getStatusCode().value() + ")"
                    + uploadHint(e));
        } catch (RestClientException e) {
            throw new IOException("Backup storage could not be reached");
        }
    }

    // An actionable hint for the two usual setup problems. Only fixed text is
    // returned - the response body itself is never repeated.
    static String uploadHint(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String body = safeBody(e);
        // Supabase states the real status in its JSON body ("statusCode":"413"),
        // also when the HTTP status is 400.
        if (status == 413 || body.contains("\"statuscode\":\"413\"")) {
            return ". The file is larger than the bucket's file size limit; raise the limit of the private backup bucket in Supabase Storage";
        }
        if (body.contains("bucket not found")) {
            return ". The private backup bucket does not exist; create it in Supabase Storage (see docs/backup.md)";
        }
        return "";
    }

    // Supabase reports a missing object as HTTP 404, or as HTTP 400 whose JSON
    // body says "not_found" / "statusCode":"404".
    static boolean isNotFound(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String body = safeBody(e);
        return status == 404 || (status == 400 && (body.contains("not_found") || body.contains("\"statuscode\":\"404\"")
                || body.contains("object not found")));
    }

    private static String safeBody(RestClientResponseException e) {
        try {
            String body = e.getResponseBodyAsString();
            return body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    @Override
    public void download(String key, OutputStream out) throws IOException {
        requireConfigured();
        try {
            restClient.get()
                    .uri("/object/{bucket}/{key}", bucket, checkKey(key))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new StatusException(response.getStatusCode().value());
                        }
                        try (InputStream in = response.getBody()) {
                            in.transferTo(out);
                        }
                        return null;
                    });
        } catch (StatusException e) {
            throw new IOException("Backup storage returned HTTP " + e.status);
        } catch (RestClientException e) {
            throw new IOException("Backup storage could not be reached");
        }
    }

    @Override
    public void delete(String key) throws IOException {
        requireConfigured();
        try {
            restClient.delete()
                    .uri("/object/{bucket}/{key}", bucket, checkKey(key))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Already gone (removed in the dashboard, or an earlier delete whose
            // database update failed): the goal of a delete is met. Otherwise
            // retention would retry it forever and the row would keep offering
            // a download that cannot work.
            if (isNotFound(e)) {
                return;
            }
            throw new IOException("Backup storage rejected the delete (HTTP " + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            throw new IOException("Backup storage could not be reached");
        }
    }

    // Unchecked on purpose: RestClient wraps an IOException thrown inside
    // exchange() into a generic ResourceAccessException, losing the status.
    private static final class StatusException extends RuntimeException {
        final int status;

        StatusException(int status) {
            super(null, null, false, false);
            this.status = status;
        }
    }

    private void requireConfigured() throws IOException {
        if (!isConfigured()) {
            throw new IOException("Backup storage is not configured");
        }
    }

    private static String checkKey(String key) {
        if (key == null || !KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("Invalid backup key");
        }
        return key;
    }
}
