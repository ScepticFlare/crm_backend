package com.compact.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

// Production storage backend for DocumentStorageService - files live as
// objects in a Supabase Storage bucket, addressed over Supabase's plain
// HTTP Storage API (no SDK dependency needed; just RestClient, already on
// the classpath via spring-boot-starter-web). Wired in place of
// LocalDiskDocumentStorageService via crm.documents.storage-backend=supabase
// (set on Render), keeping uploaded documents alive across
// redeploys/restarts/spin-downs/new instances despite the local filesystem
// being ephemeral there. DocumentService/LeadEmailService/DocumentController
// are unaffected - they only ever depend on the DocumentStorageService
// interface, never on which implementation is active.
//
// The service-role key is used because uploads/downloads/deletes happen
// only from this backend (never the browser) and the bucket is private -
// see application.properties for the required env vars. That key never
// leaves the backend.
@Slf4j
@Service
@ConditionalOnProperty(prefix = "crm.documents", name = "storage-backend", havingValue = "supabase")
public class SupabaseStorageDocumentStorageService implements DocumentStorageService {

    private final RestClient restClient;
    private final String bucket;

    // Explicitly @Autowired because a second (package-private, test-only)
    // constructor also exists below - Spring's implicit single-constructor
    // injection only applies when there's exactly one non-default
    // constructor, so without this marker bean creation fails at startup
    // with "No default constructor found".
    @Autowired
    public SupabaseStorageDocumentStorageService(
            @Value("${crm.documents.supabase.url}") String supabaseUrl,
            @Value("${crm.documents.supabase.service-role-key}") String serviceRoleKey,
            @Value("${crm.documents.supabase.bucket}") String bucket
    ) {
        this(RestClient.builder()
                .baseUrl(supabaseUrl.replaceAll("/+$", "") + "/storage/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceRoleKey)
                .defaultHeader("apikey", serviceRoleKey), bucket);
    }

    // Package-private: lets tests bind a MockRestServiceServer to the
    // builder instead of hitting a real Supabase project.
    SupabaseStorageDocumentStorageService(RestClient.Builder restClientBuilder, String bucket) {
        this.bucket = bucket;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String store(MultipartFile file) throws IOException {

        // Same key shape as LocalDiskDocumentStorageService (a freshly
        // generated UUID + original extension, never the caller-supplied
        // filename) - keeps the storage key safe from path traversal /
        // control characters and identical in format regardless of which
        // backend is active.
        String extension = extensionOf(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + extension;

        byte[] bytes = file.getBytes();
        MediaType contentType = file.getContentType() != null
                ? MediaType.parseMediaType(file.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        try {
            restClient.post()
                    .uri("/object/{bucket}/{path}", bucket, storedFileName)
                    .contentType(contentType)
                    .body(bytes)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IOException("Unable to upload the file to Supabase Storage.", e);
        }

        return storedFileName;
    }

    @Override
    public byte[] load(String storedFileName) throws IOException {
        try {
            return restClient.get()
                    .uri("/object/{bucket}/{path}", bucket, storedFileName)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new IOException("Document not found in Supabase Storage: " + storedFileName);
                    })
                    .body(byte[].class);
        } catch (RestClientException e) {
            throw new IOException("Unable to read the stored file from Supabase Storage.", e);
        }
    }

    @Override
    public void delete(String storedFileName) throws IOException {
        try {
            restClient.delete()
                    .uri("/object/{bucket}/{path}", bucket, storedFileName)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IOException("Unable to delete the stored file from Supabase Storage.", e);
        }
    }

    private String extensionOf(String originalFilename) {

        if (originalFilename == null) {
            return "";
        }

        int dotIndex = originalFilename.lastIndexOf('.');

        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            return "";
        }

        String extension = originalFilename.substring(dotIndex).replaceAll("[^a-zA-Z0-9.]", "");

        return extension.length() <= 10 ? extension : "";
    }

}
