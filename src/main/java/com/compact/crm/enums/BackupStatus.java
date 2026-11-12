package com.compact.crm.enums;

// Lifecycle of one database backup (entity.DbBackup). RUNNING -> SUCCEEDED once
// the dump has been verified and uploaded, RUNNING -> FAILED otherwise
// (including a server restart while it was running).
public enum BackupStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}
