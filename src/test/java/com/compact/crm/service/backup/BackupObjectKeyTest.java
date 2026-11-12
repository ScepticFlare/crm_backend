package com.compact.crm.service.backup;

import com.compact.crm.entity.DbBackup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

// The backup file name says "...Z" (UTC). startedAt is the server's local
// wall-clock time, so on a server that is not on UTC (a developer machine in
// India) the name used to carry the local time with a "Z" - 5h30 off.
class BackupObjectKeyTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restore() {
        TimeZone.setDefault(original);
    }

    @Test
    void theTimestampInTheFileName_isRealUtc_whateverTheServerZone() {

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        DbBackup ist = DbBackup.builder().id(7L).startedAt(LocalDateTime.of(2026, 11, 12, 10, 0, 0)).build();
        assertThat(BackupService.objectKey(ist)).isEqualTo("crm-backup-20261112T043000Z-7.dump");

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));                   // Render
        DbBackup utc = DbBackup.builder().id(8L).startedAt(LocalDateTime.of(2026, 11, 12, 4, 30, 0)).build();
        assertThat(BackupService.objectKey(utc)).isEqualTo("crm-backup-20261112T043000Z-8.dump");
    }
}
