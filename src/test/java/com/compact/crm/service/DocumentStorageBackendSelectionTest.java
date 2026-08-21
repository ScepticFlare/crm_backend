package com.compact.crm.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the crm.documents.storage-backend switch wires exactly one
// DocumentStorageService bean, and the right implementation - the same
// property resolution this repo's real application.properties uses
// (DOCUMENT_STORAGE_BACKEND env var), just supplied directly here instead
// of via env vars so the test needs no real Supabase project.
class DocumentStorageBackendSelectionTest {

    // Only scans this package's two DocumentStorageService implementations -
    // a plain ApplicationContextRunner.withUserConfiguration would also work,
    // but component-scanning them keeps this test honest about the real
    // @Service/@ConditionalOnProperty annotations actually driving selection.
    @Configuration
    @ComponentScan(
            basePackageClasses = DocumentStorageService.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    LocalDiskDocumentStorageService.class,
                    SupabaseStorageDocumentStorageService.class
            })
    )
    static class StorageConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfig.class);

    @Test
    void noBackendConfigured_wiresLocalDiskOnly() {
        contextRunner
                .withPropertyValues("crm.documents.storage-path=target/test-documents")
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentStorageService.class);
                    assertThat(context).hasSingleBean(LocalDiskDocumentStorageService.class);
                    assertThat(context).doesNotHaveBean(SupabaseStorageDocumentStorageService.class);
                });
    }

    @Test
    void supabaseBackendConfigured_wiresSupabaseOnly() {
        contextRunner
                .withPropertyValues(
                        "crm.documents.storage-backend=supabase",
                        "crm.documents.supabase.url=https://nhoqidvsxpnfmcudefgb.supabase.co",
                        "crm.documents.supabase.service-role-key=test-key",
                        "crm.documents.supabase.bucket=documents"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentStorageService.class);
                    assertThat(context).hasSingleBean(SupabaseStorageDocumentStorageService.class);
                    assertThat(context).doesNotHaveBean(LocalDiskDocumentStorageService.class);
                });
    }

}
