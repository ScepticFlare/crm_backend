package com.compact.crm.service.backup;

// A backup step failed. The message is shown to the admin and stored on the
// db_backup row, so it must already be safe: fixed text, or tool output that
// went through SecretScrubber first. Never a raw exception message.
public class BackupFailedException extends RuntimeException {

    public BackupFailedException(String message) {
        super(message);
    }
}
