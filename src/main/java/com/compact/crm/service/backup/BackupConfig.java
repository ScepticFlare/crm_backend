package com.compact.crm.service.backup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Wiring for "Take Backup". Backups run on their own single background thread
// (never a request thread, never the shared Hikari pool - pg_dump opens its own
// connection), and only one runs at a time (see BackupService).
@Configuration
@EnableConfigurationProperties(BackupProperties.class)
public class BackupConfig {

    public static final String EXECUTOR = "backupExecutor";

    // shutdownNow on context close interrupts a running job, which kills
    // pg_dump and records the backup as FAILED instead of leaving it RUNNING.
    @Bean(name = EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService backupExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "crm-backup");
            t.setDaemon(true);
            return t;
        });
    }

    // Same Supabase project credentials as the document storage; the bucket is
    // a separate, private one (crm.backup.bucket, default crm-backups).
    @Bean
    public BackupStorage backupStorage(
            @Value("${crm.backup.supabase-url:${SUPABASE_URL:}}") String supabaseUrl,
            @Value("${crm.backup.supabase-service-role-key:${SUPABASE_SERVICE_ROLE_KEY:}}") String serviceRoleKey,
            BackupProperties properties) {
        return new SupabaseBackupStorage(supabaseUrl, serviceRoleKey, properties.getBucket(), properties.getUploadTimeout());
    }

    @Bean
    public DatabaseDumper databaseDumper(
            BackupProperties properties,
            @Value("${spring.datasource.url:}") String jdbcUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password) {
        return new PgDatabaseDumper(properties, new ProcessCommandExecutor(properties.getTempDir()), jdbcUrl, username, password);
    }
}
