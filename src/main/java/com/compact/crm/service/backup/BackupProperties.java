package com.compact.crm.service.backup;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

// Settings for "Take Backup" (crm.backup.*). Every value has a production-safe
// default, so nothing has to be set on Render beyond the Supabase Storage
// credentials the document storage already uses (SUPABASE_URL,
// SUPABASE_SERVICE_ROLE_KEY - see SupabaseBackupStorage). Any value can be
// overridden with the matching environment variable, e.g. CRM_BACKUP_RETAIN=30.
@Getter
@Setter
@ConfigurationProperties(prefix = "crm.backup")
public class BackupProperties {

    // Private Supabase Storage bucket the dumps are uploaded to. Must exist and
    // must NOT be public (see docs/backup.md).
    private String bucket = "crm-backups";

    // How many successful backups to keep in the bucket; older dumps are deleted.
    private int retain = 14;

    private String pgDumpPath = "pg_dump";
    private String pgRestorePath = "pg_restore";

    // Hard limits for each step; a step that runs longer is killed and the backup fails.
    private Duration dumpTimeout = Duration.ofMinutes(15);
    private Duration verifyTimeout = Duration.ofMinutes(5);
    private Duration uploadTimeout = Duration.ofMinutes(10);

    // A RUNNING row older than this belongs to a job that can no longer be
    // alive (it would have hit the step timeouts above) and is marked FAILED.
    private Duration staleAfter = Duration.ofMinutes(45);

    // The backup refuses to start a dump with less free space than this in tempDir.
    private long minFreeMb = 256;

    // Where the dump is written before it is checked and uploaded. Deleted afterwards.
    private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "crm-backups");

    // libpq sslmode for pg_dump. Empty = the JDBC URL's sslmode, else "require"
    // (or "prefer" for a database on localhost).
    private String sslMode = "";

    // Tables whose data must be in the archive for it to count as complete.
    private List<String> requiredTables = List.of("employees", "flyway_schema_history");
}
