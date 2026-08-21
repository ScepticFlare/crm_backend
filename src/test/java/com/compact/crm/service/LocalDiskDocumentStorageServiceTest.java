package com.compact.crm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskDocumentStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void store_thenLoad_roundTripsTheSameBytes() throws IOException {

        DocumentStorageService storage = new LocalDiskDocumentStorageService(tempDir.toString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "brochure.pdf", "application/pdf", "hello world".getBytes());

        String storedFileName = storage.store(file);

        assertThat(storedFileName).endsWith(".pdf");
        assertThat(storage.load(storedFileName)).isEqualTo("hello world".getBytes());
    }

    @Test
    void store_neverUsesOriginalFilenameAsTheStorageKey() throws IOException {

        DocumentStorageService storage = new LocalDiskDocumentStorageService(tempDir.toString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd", "text/plain", "x".getBytes());

        String storedFileName = storage.store(file);

        assertThat(storedFileName).doesNotContain("..").doesNotContain("/").doesNotContain("\\");
    }

    @Test
    void delete_removesTheFile_subsequentLoadFails() throws IOException {

        DocumentStorageService storage = new LocalDiskDocumentStorageService(tempDir.toString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "brochure.pdf", "application/pdf", "hello".getBytes());
        String storedFileName = storage.store(file);

        storage.delete(storedFileName);

        assertThatThrownBy(() -> storage.load(storedFileName)).isInstanceOf(IOException.class);
    }

}
