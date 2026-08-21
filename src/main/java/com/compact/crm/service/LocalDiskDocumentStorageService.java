package com.compact.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

// V1 storage backend for DocumentStorageService - files live under
// crm.documents.storage-path on local disk. Fine for local development;
// not suitable for a multi-instance/ephemeral-filesystem deployment (e.g.
// Render), which is exactly why callers only ever depend on the
// DocumentStorageService interface. In production, SupabaseStorageDocumentStorageService
// is wired instead via crm.documents.storage-backend=supabase - this class
// stays the default (matchIfMissing) so local dev/tests need no config.
@Slf4j
@Service
@ConditionalOnProperty(prefix = "crm.documents", name = "storage-backend", havingValue = "local", matchIfMissing = true)
public class LocalDiskDocumentStorageService implements DocumentStorageService {

    private final Path storageRoot;

    public LocalDiskDocumentStorageService(@Value("${crm.documents.storage-path}") String storagePath) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @Override
    public String store(MultipartFile file) throws IOException {

        Files.createDirectories(storageRoot);

        // The stored filename is always a freshly generated UUID, never
        // derived from the caller-supplied original filename - only the
        // extension is carried over, and only for readability on disk. This
        // is what keeps the storage key safe from path traversal / control
        // characters in a user-supplied filename, without needing to
        // sanitize arbitrary input.
        String extension = extensionOf(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + extension;

        Path target = storageRoot.resolve(storedFileName);

        file.transferTo(target);

        return storedFileName;
    }

    @Override
    public byte[] load(String storedFileName) throws IOException {

        Path target = resolveSafely(storedFileName);

        return Files.readAllBytes(target);
    }

    @Override
    public void delete(String storedFileName) throws IOException {

        Path target = resolveSafely(storedFileName);

        Files.deleteIfExists(target);
    }

    // storedFileName is always a value this service itself generated (a
    // UUID + extension, see store()), never taken from a request path
    // segment - but resolving strictly against storageRoot and rejecting
    // anything that escapes it is a cheap, worthwhile guard regardless.
    private Path resolveSafely(String storedFileName) throws IOException {

        Path target = storageRoot.resolve(storedFileName).normalize();

        if (!target.startsWith(storageRoot)) {
            throw new IOException("Invalid document storage key.");
        }

        return target;
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
