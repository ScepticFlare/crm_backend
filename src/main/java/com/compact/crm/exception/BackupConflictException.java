package com.compact.crm.exception;

// A backup request that conflicts with the current state: another backup is
// already running, or the requested backup cannot be downloaded (failed, still
// running, or removed by retention). Mapped to 409 in GlobalExceptionHandler.
public class BackupConflictException extends RuntimeException {

    public static final String IN_PROGRESS = "BACKUP_IN_PROGRESS";
    public static final String NOT_AVAILABLE = "BACKUP_NOT_AVAILABLE";

    private final String code;

    public BackupConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
