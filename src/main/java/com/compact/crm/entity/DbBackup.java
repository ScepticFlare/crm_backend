package com.compact.crm.entity;

import com.compact.crm.enums.BackupStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// One "Take Backup" attempt (see service.backup.BackupService and migration
// V14_2). The dump bytes are never stored here - only where they live in the
// private backup bucket (objectKey) and how to check them (size, SHA-256,
// table count, PostgreSQL versions). errorMessage is a short scrubbed reason
// that is safe to show to an admin; it never contains connection details.
@Entity
@Table(name = "db_backup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbBackup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BackupStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiated_by_employee_id")
    private Employee initiatedBy;

    @Column(name = "initiated_by_name", nullable = false)
    private String initiatedByName;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(name = "object_key", length = 200)
    private String objectKey;

    @Column(name = "table_count")
    private Integer tableCount;

    @Column(name = "server_version", length = 60)
    private String serverVersion;

    @Column(name = "pg_dump_version", length = 60)
    private String pgDumpVersion;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;
}
