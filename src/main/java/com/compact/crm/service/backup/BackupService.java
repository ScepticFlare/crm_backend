package com.compact.crm.service.backup;

import com.compact.crm.dto.response.BackupResponse;
import com.compact.crm.entity.DbBackup;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.BackupStatus;
import com.compact.crm.exception.BackupConflictException;
import com.compact.crm.exception.IntegrationNotConfiguredException;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.DbBackupRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.service.ActivityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

// "Take Backup" (ADMIN only). start() records a RUNNING backup and hands the
// work to the single backup thread; the request returns immediately (202) and
// the page polls get(). The job:
//   1. checks free disk space,
//   2. pg_dump -Fc of the public schema into a private temp file,
//   3. rejects an empty file, then pg_restore --list must read the whole
//      archive and find data for the required tables (a truncated or corrupt
//      dump fails here),
//   4. computes the SHA-256,
//   5. uploads to the private backup bucket (last step - nothing is marked
//      SUCCEEDED before the file is safely stored),
//   6. deletes dumps beyond the retention count.
// Any failure marks the backup FAILED with a scrubbed reason; the temp file is
// always deleted. Only one backup runs at a time (in-process flag + a partial
// unique index on db_backup), and every start, outcome and download is written
// to the activity log.
//
// ADMIN is enforced twice: @PreAuthorize on BackupController and requireAdmin()
// here, so the service is safe even if a future caller forgets the annotation.
@Slf4j
@Service
public class BackupService {

    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final int MAX_ERROR = 500;

    private final DbBackupRepository repository;
    private final EmployeeRepository employeeRepository;
    private final DatabaseDumper dumper;
    private final BackupStorage storage;
    private final BackupProperties properties;
    private final ActivityLogService activityLogService;
    private final CurrentUserService currentUserService;
    private final ExecutorService executor;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public BackupService(DbBackupRepository repository,
                         EmployeeRepository employeeRepository,
                         DatabaseDumper dumper,
                         BackupStorage storage,
                         BackupProperties properties,
                         ActivityLogService activityLogService,
                         CurrentUserService currentUserService,
                         @Qualifier(BackupConfig.EXECUTOR) ExecutorService executor) {
        this.repository = repository;
        this.employeeRepository = employeeRepository;
        this.dumper = dumper;
        this.storage = storage;
        this.properties = properties;
        this.activityLogService = activityLogService;
        this.currentUserService = currentUserService;
        this.executor = executor;
    }

    public record DownloadTicket(Long id, String key, String fileName, Long sizeBytes, String sha256) {
    }

    // ---- API -------------------------------------------------------------

    public BackupResponse start() {

        Employee actor = requireAdmin();

        if (!storage.isConfigured()) {
            throw new IntegrationNotConfiguredException(
                    "Backups are not configured on this server (backup storage is missing).");
        }

        if (!running.compareAndSet(false, true)) {
            throw inProgress();
        }

        DbBackup backup;
        try {
            failStale();
            if (repository.findFirstByStatusOrderByStartedAtDesc(BackupStatus.RUNNING).isPresent()) {
                throw inProgress();
            }
            try {
                backup = repository.saveAndFlush(DbBackup.builder()
                        .status(BackupStatus.RUNNING)
                        .initiatedBy(actor)
                        .initiatedByName(actor.getName())
                        .startedAt(LocalDateTime.now())
                        .build());
            } catch (DataIntegrityViolationException e) {
                // another instance inserted its RUNNING row first (uq_db_backup_one_running)
                throw inProgress();
            }
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }

        activityLogService.log(actor, ActivityModule.SYSTEM, ActivityAction.BACKUP_STARTED,
                backup.getId(), entityName(backup.getId()), "Started a database backup");

        Long id = backup.getId();
        Long actorId = actor.getId();
        try {
            executor.execute(() -> runJob(id, actorId));
        } catch (RejectedExecutionException e) {
            running.set(false);
            markFailed(id, actorId, "The backup could not be started because the server is shutting down.");
            throw new IntegrationNotConfiguredException("The server is shutting down; please try again in a minute.");
        }

        return toResponse(backup);
    }

    public List<BackupResponse> list() {
        requireAdmin();
        return repository.findTop20ByOrderByStartedAtDescIdDesc().stream().map(this::toResponse).toList();
    }

    public BackupResponse get(Long id) {
        requireAdmin();
        return toResponse(find(id));
    }

    public DownloadTicket prepareDownload(Long id) {

        Employee actor = requireAdmin();
        DbBackup backup = find(id);

        if (!isAvailable(backup)) {
            String why = switch (backup.getStatus()) {
                case RUNNING -> "This backup is still running.";
                case FAILED -> "This backup failed, so there is no file to download.";
                case SUCCEEDED -> "This backup file was removed by the retention policy.";
            };
            throw new BackupConflictException(BackupConflictException.NOT_AVAILABLE, why);
        }

        activityLogService.log(actor, ActivityModule.SYSTEM, ActivityAction.BACKUP_DOWNLOADED,
                backup.getId(), entityName(backup.getId()), "Downloaded database backup " + fileName(backup));

        return new DownloadTicket(backup.getId(), backup.getObjectKey(), fileName(backup), backup.getSizeBytes(), backup.getSha256());
    }

    public void streamDownload(DownloadTicket ticket, OutputStream out) throws IOException {
        storage.download(ticket.key(), out);
    }

    // ---- recovery --------------------------------------------------------

    // A backup that was RUNNING when the previous server process stopped can
    // never finish: record it as FAILED so the history is truthful and a new
    // backup can start.
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedBackups() {
        try {
            for (DbBackup b : repository.findByStatus(BackupStatus.RUNNING)) {
                finish(b, BackupStatus.FAILED, "Interrupted: the server restarted while this backup was running.");
                log.warn("Backup {} was still RUNNING at startup and has been marked FAILED", b.getId());
            }
        } catch (RuntimeException e) {
            log.error("Could not check for interrupted backups at startup: {}", e.getClass().getSimpleName());
        }
    }

    private void failStale() {
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.getStaleAfter());
        for (DbBackup b : repository.findByStatus(BackupStatus.RUNNING)) {
            if (b.getStartedAt().isBefore(cutoff)) {
                finish(b, BackupStatus.FAILED, "Interrupted: the backup stopped responding and was abandoned.");
                log.warn("Backup {} was RUNNING for longer than {} and has been marked FAILED", b.getId(), properties.getStaleAfter());
            }
        }
    }

    // ---- the job ---------------------------------------------------------

    void runJob(Long id, Long actorId) {

        Path archive = null;
        long t0 = System.nanoTime();
        try {
            ensureFreeSpace();
            archive = createPrivateTempFile(id);

            dumper.dump(archive);

            long size = Files.size(archive);
            if (size == 0) {
                throw new BackupFailedException("pg_dump produced an empty file.");
            }

            PgDumpRunner.ArchiveInfo info = dumper.verify(archive);
            String sha256 = sha256(archive);

            DbBackup backup = find(id);
            String key = objectKey(backup);
            uploadOrClean(key, archive);

            backup = find(id);
            backup.setSizeBytes(size);
            backup.setSha256(sha256);
            backup.setObjectKey(key);
            backup.setTableCount(info.tableCount());
            backup.setServerVersion(info.serverVersion());
            backup.setPgDumpVersion(info.pgDumpVersion());
            finish(backup, BackupStatus.SUCCEEDED, null);

            log.info("Backup {} succeeded: {} bytes, {} tables, {} ms", id, size, info.tableCount(),
                    Duration.ofNanos(System.nanoTime() - t0).toMillis());
            activityLogService.log(actor(actorId), ActivityModule.SYSTEM, ActivityAction.BACKUP_SUCCEEDED, id, entityName(id),
                    "Database backup completed (" + size + " bytes, " + info.tableCount() + " tables)");

            applyRetention();

        } catch (BackupFailedException e) {
            log.warn("Backup {} failed: {}", id, e.getMessage());
            markFailed(id, actorId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Backup {} was interrupted (server shutting down)", id);
            markFailed(id, actorId, "Interrupted: the server was shutting down.");
        } catch (Exception e) {
            // never surface a raw exception message: it may name hosts or URLs
            log.error("Backup {} failed unexpectedly ({})", id, e.getClass().getSimpleName());
            markFailed(id, actorId, "The backup failed unexpectedly (" + e.getClass().getSimpleName() + ").");
        } finally {
            deleteQuietly(archive);
            running.set(false);
        }
    }

    private void uploadOrClean(String key, Path archive) {
        try {
            storage.upload(key, archive);
        } catch (IOException e) {
            // the object may exist half-written; never leave it behind
            try {
                storage.delete(key);
            } catch (IOException | RuntimeException ignored) {
                // nothing more to do; retention never lists FAILED rows' keys
            }
            throw new BackupFailedException("Uploading the backup to private storage failed: " + e.getMessage() + ".");
        }
    }

    // Keep the newest `retain` successful backups; delete the dump of every older one.
    void applyRetention() {
        int retain = Math.max(1, properties.getRetain());
        List<DbBackup> stored = repository.findByStatusAndRemovedAtIsNullOrderByStartedAtDescIdDesc(BackupStatus.SUCCEEDED);
        for (DbBackup old : stored.stream().skip(retain).toList()) {
            try {
                if (old.getObjectKey() != null) {
                    storage.delete(old.getObjectKey());
                }
                old.setRemovedAt(LocalDateTime.now());
                repository.save(old);
                log.info("Backup {} removed by retention (keeping the newest {})", old.getId(), retain);
            } catch (IOException | RuntimeException e) {
                log.warn("Backup {} could not be removed by retention; will retry after the next backup ({})",
                        old.getId(), e.getMessage());
            }
        }
    }

    private void markFailed(Long id, Long actorId, String reason) {
        try {
            DbBackup backup = find(id);
            finish(backup, BackupStatus.FAILED, reason);
            activityLogService.log(actor(actorId), ActivityModule.SYSTEM, ActivityAction.BACKUP_FAILED, id, entityName(id),
                    "Database backup failed: " + truncate(reason, 400));
        } catch (RuntimeException e) {
            log.error("Could not record the failure of backup {} ({})", id, e.getClass().getSimpleName());
        }
    }

    private void finish(DbBackup backup, BackupStatus status, String error) {
        backup.setStatus(status);
        backup.setFinishedAt(LocalDateTime.now());
        backup.setErrorMessage(error == null ? null : truncate(error, MAX_ERROR));
        repository.saveAndFlush(backup);
    }

    private void ensureFreeSpace() throws IOException {
        Path dir = properties.getTempDir();
        Files.createDirectories(dir);
        long freeMb = Files.getFileStore(dir).getUsableSpace() / (1024 * 1024);
        if (freeMb < properties.getMinFreeMb()) {
            throw new BackupFailedException("Not enough free disk space on the server for a backup ("
                    + freeMb + " MB free, " + properties.getMinFreeMb() + " MB required).");
        }
    }

    private Path createPrivateTempFile(Long id) throws IOException {
        Path dir = properties.getTempDir();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(dir, "backup-" + id + "-", ".dump",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(dir, "backup-" + id + "-", ".dump");
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Could not delete temporary backup file {}", file.getFileName());
        }
    }

    // ---- helpers ---------------------------------------------------------

    boolean isRunning() {
        return running.get();
    }

    private Employee requireAdmin() {
        Employee current = currentUserService.getCurrentEmployee();
        if (current == null || current.getRole() == null || !"ADMIN".equals(current.getRole().getName())) {
            throw new AccessDeniedException("Only an ADMIN can manage backups.");
        }
        return current;
    }

    private DbBackup find(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Backup not found."));
    }

    private Employee actor(Long actorId) {
        return actorId == null ? null : employeeRepository.findById(actorId).orElse(null);
    }

    private static BackupConflictException inProgress() {
        return new BackupConflictException(BackupConflictException.IN_PROGRESS,
                "A backup is already in progress. Please wait for it to finish.");
    }

    private static boolean isAvailable(DbBackup b) {
        return b.getStatus() == BackupStatus.SUCCEEDED && b.getRemovedAt() == null && b.getObjectKey() != null;
    }

    static String objectKey(DbBackup b) {
        // startedAt is the server's local wall-clock time (LocalDateTime.now());
        // convert it, so the "...Z" in the file name really is UTC on any server.
        return "crm-backup-" + b.getStartedAt().atZone(java.time.ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).format(KEY_TIME) + "-" + b.getId() + ".dump";
    }

    private static String fileName(DbBackup b) {
        return b.getObjectKey() != null ? b.getObjectKey() : objectKey(b);
    }

    private static String entityName(Long id) {
        return "Database backup #" + id;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    BackupResponse toResponse(DbBackup b) {
        Long duration = b.getFinishedAt() == null ? null : Duration.between(b.getStartedAt(), b.getFinishedAt()).toMillis();
        return BackupResponse.builder()
                .id(b.getId())
                .status(b.getStatus())
                .startedAt(b.getStartedAt())
                .finishedAt(b.getFinishedAt())
                .durationMs(duration)
                .sizeBytes(b.getSizeBytes())
                .sha256(b.getSha256())
                .tableCount(b.getTableCount())
                .serverVersion(b.getServerVersion())
                .pgDumpVersion(b.getPgDumpVersion())
                .initiatedByName(b.getInitiatedByName())
                .errorMessage(b.getErrorMessage())
                .available(isAvailable(b))
                .fileName(b.getStatus() == BackupStatus.SUCCEEDED ? fileName(b) : null)
                .build();
    }
}
