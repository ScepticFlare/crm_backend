package com.compact.crm.dto.response;

import com.compact.crm.enums.BackupStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// What an admin sees about one backup. Deliberately carries no connection
// details, storage URL or raw tool output - only status, timing, size, the
// SHA-256 to check a download against, and a scrubbed failure reason.
@Getter
@Builder
public class BackupResponse {

    private Long id;
    private BackupStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Long sizeBytes;
    private String sha256;
    private Integer tableCount;
    private String serverVersion;
    private String pgDumpVersion;
    private String initiatedByName;
    private String errorMessage;
    // true while the dump can still be downloaded (SUCCEEDED, not yet removed by retention)
    private boolean available;
    private String fileName;
}
