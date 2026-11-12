package com.compact.crm.service.backup;

import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.DbBackup;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.BackupStatus;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.DbBackupRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/admin/backups over HTTP with the real security chain, the real
 * BackupService, its background thread and the real db_backup table (H2).
 * pg_dump / pg_restore (DatabaseDumper) and the Supabase bucket (BackupStorage)
 * are mocks, so nothing here touches a real database dump or a real bucket;
 * the real tools are covered by PgDumpRunnerTest / ProcessCommandExecutorTest
 * and, against a disposable PostgreSQL, by BackupPostgresRoundTripTest.
 *
 * Not @Transactional on purpose: the backup runs on another thread, which can
 * only see committed rows. Every row this class creates is deleted afterwards.
 */
@SpringBootTest(properties = {
        "crm.backup.temp-dir=target/test-backups",
        "crm.backup.retain=2"
})
@AutoConfigureMockMvc
class BackupApiTest {

    private static final byte[] DUMP = "PGDMP fake archive bytes".getBytes();
    private static final PgDumpRunner.ArchiveInfo INFO = new PgDumpRunner.ArchiveInfo(
            12, Set.of("public.employees", "public.flyway_schema_history"), "17.4", "17.6");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DbBackupRepository backupRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private BackupService backupService;

    @MockitoBean private DatabaseDumper dumper;
    @MockitoBean private BackupStorage storage;

    private Employee admin;
    private Employee manager;
    private Employee employee;

    @BeforeEach
    void setUp() throws Exception {

        backupRepository.deleteAll();

        admin = employee("ADMIN", 100);
        manager = employee("MANAGER", 50);
        employee = employee("EMPLOYEE", 10);

        when(storage.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            Files.write(inv.getArgument(0, Path.class), DUMP);
            return null;
        }).when(dumper).dump(any());
        when(dumper.verify(any())).thenReturn(INFO);
    }

    @AfterEach
    void tearDown() {
        awaitIdle();
        backupRepository.deleteAll();
        List<Long> ids = Stream.of(admin, manager, employee).map(Employee::getId).toList();
        activityLogRepository.deleteAll(activityLogRepository.findAll().stream()
                .filter(a -> a.getEmployee() != null && ids.contains(a.getEmployee().getId())).toList());
        employeeRepository.deleteAllById(ids);
    }

    private Employee employee(String roleName, int rank) {
        Role role = roleRepository.findByNameIgnoreCase(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).rank(rank).build()));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return employeeRepository.save(Employee.builder().name(roleName + " " + tag)
                .email("backup-" + tag + "@company.test")
                .phone("98" + String.format("%08d", Math.abs(tag.hashCode()) % 100_000_000))
                .password("x").role(role).isActive(true).build());
    }

    private String bearer(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }

    private long startBackup() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/admin/backups").header("Authorization", bearer(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private DbBackup awaitFinished(long id) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            DbBackup b = backupRepository.findById(id).orElseThrow();
            if (b.getStatus() != BackupStatus.RUNNING && !backupService.isRunning()) {
                return b;
            }
            sleep();
        }
        throw new AssertionError("backup " + id + " did not finish");
    }

    private void awaitIdle() {
        long deadline = System.currentTimeMillis() + 15_000;
        while (backupService.isRunning() && System.currentTimeMillis() < deadline) {
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<ActivityAction> backupActivity(long backupId) {
        return activityLogRepository.findAll().stream()
                .filter(a -> a.getModule() == ActivityModule.SYSTEM && Long.valueOf(backupId).equals(a.getEntityId()))
                .map(ActivityLog::getAction).toList();
    }

    private void assertNoTempFilesLeft() throws IOException {
        Path dir = Path.of("target/test-backups");
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                assertThat(files.filter(p -> p.getFileName().toString().startsWith("backup-"))).isEmpty();
            }
        }
    }

    // ---- authorization ---------------------------------------------------

    @Test
    void everyEndpoint_requiresAuthentication() throws Exception {

        mockMvc.perform(post("/api/admin/backups")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/backups")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/backups/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/backups/1/download")).andExpect(status().isUnauthorized());

        verify(dumper, never()).dump(any());
    }

    @Test
    void employeesAndManagers_areForbiddenEverywhere_evenCallingTheApiDirectly() throws Exception {

        DbBackup done = backupRepository.save(DbBackup.builder().status(BackupStatus.SUCCEEDED)
                .initiatedByName("x").startedAt(LocalDateTime.now()).finishedAt(LocalDateTime.now())
                .objectKey("crm-backup-20261111T100000Z-1.dump").sizeBytes(3L).build());

        for (Employee e : List.of(employee, manager)) {
            mockMvc.perform(post("/api/admin/backups").header("Authorization", bearer(e))).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/backups").header("Authorization", bearer(e))).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/backups/" + done.getId()).header("Authorization", bearer(e)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/backups/" + done.getId() + "/download").header("Authorization", bearer(e)))
                    .andExpect(status().isForbidden());
        }

        verify(dumper, never()).dump(any());
        verify(storage, never()).download(anyString(), any());
        assertThat(backupRepository.count()).isEqualTo(1);
    }

    // ---- success -----------------------------------------------------------

    @Test
    void admin_takesABackup_thatIsVerifiedUploadedAndAudited() throws Exception {

        long id = startBackup();
        DbBackup b = awaitFinished(id);

        assertThat(b.getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        assertThat(b.getSizeBytes()).isEqualTo(DUMP.length);
        assertThat(b.getSha256()).hasSize(64);
        assertThat(b.getObjectKey()).matches("crm-backup-\\d{8}T\\d{6}Z-" + id + "\\.dump");
        assertThat(b.getTableCount()).isEqualTo(12);
        assertThat(b.getServerVersion()).isEqualTo("17.4");
        assertThat(b.getInitiatedByName()).isEqualTo(admin.getName());

        verify(storage).upload(eq(b.getObjectKey()), any());
        assertThat(backupActivity(id)).containsExactly(ActivityAction.BACKUP_STARTED, ActivityAction.BACKUP_SUCCEEDED);
        assertNoTempFilesLeft();

        String json = mockMvc.perform(get("/api/admin/backups/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.sha256").value(b.getSha256()))
                .andExpect(jsonPath("$.initiatedByName").value(admin.getName()))
                .andReturn().getResponse().getContentAsString();

        // nothing about where or how it is stored
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.has("objectKey")).isFalse();
        assertThat(json).doesNotContain("supabase").doesNotContain("jdbc");

        mockMvc.perform(get("/api/admin/backups").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void download_streamsTheFileToAnAdmin_privatelyAndAudited() throws Exception {

        long id = startBackup();
        DbBackup b = awaitFinished(id);
        doAnswer(inv -> {
            inv.getArgument(1, OutputStream.class).write(DUMP);
            return null;
        }).when(storage).download(eq(b.getObjectKey()), any());

        mockMvc.perform(get("/api/admin/backups/" + id + "/download").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(DUMP))
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(b.getObjectKey())))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        assertThat(backupActivity(id)).contains(ActivityAction.BACKUP_DOWNLOADED);
    }

    @Test
    void download_whenStorageFails_isAJsonError_notAFile() throws Exception {

        long id = startBackup();
        DbBackup b = awaitFinished(id);
        doThrow(new IOException("Backup storage returned HTTP 404")).when(storage).download(eq(b.getObjectKey()), any());

        mockMvc.perform(get("/api/admin/backups/" + id + "/download").header("Authorization", bearer(admin)))
                .andExpect(status().isBadGateway())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(jsonPath("$.message").value(containsString("could not be read")));
    }

    @Test
    void download_ofAFailedRunningRemovedOrUnknownBackup_isRefused() throws Exception {

        DbBackup failed = backupRepository.save(DbBackup.builder().status(BackupStatus.FAILED)
                .initiatedByName("x").startedAt(LocalDateTime.now()).errorMessage("boom").build());
        DbBackup removed = backupRepository.save(DbBackup.builder().status(BackupStatus.SUCCEEDED)
                .initiatedByName("x").startedAt(LocalDateTime.now()).objectKey("crm-backup-20200101T000000Z-1.dump")
                .removedAt(LocalDateTime.now()).build());

        mockMvc.perform(get("/api/admin/backups/" + failed.getId() + "/download").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BACKUP_NOT_AVAILABLE"));
        mockMvc.perform(get("/api/admin/backups/" + removed.getId() + "/download").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("retention")));
        mockMvc.perform(get("/api/admin/backups/999999/download").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/backups/999999").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());

        verify(storage, never()).download(anyString(), any());
    }

    // ---- concurrency -----------------------------------------------------

    @Test
    void aSecondBackupWhileOneIsRunning_isRefusedWith409() throws Exception {

        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            Files.write(inv.getArgument(0, Path.class), DUMP);
            return null;
        }).when(dumper).dump(any());

        long id = startBackup();

        mockMvc.perform(post("/api/admin/backups").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BACKUP_IN_PROGRESS"));

        release.countDown();
        assertThat(awaitFinished(id).getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        assertThat(backupRepository.count()).isEqualTo(1);

        // and once it has finished, the next one may start
        assertThat(awaitFinished(startBackup()).getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
    }

    // ---- failures --------------------------------------------------------

    @Test
    void aFailedDump_isRecordedAsFailed_withTheScrubbedReason_andNothingIsUploaded() throws Exception {

        doThrow(new BackupFailedException("pg_dump failed (exit 1): FATAL: password authentication failed for user \"[redacted]\""))
                .when(dumper).dump(any());

        long id = startBackup();
        DbBackup b = awaitFinished(id);

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).startsWith("pg_dump failed (exit 1)");
        verify(storage, never()).upload(anyString(), any());
        assertThat(backupActivity(id)).containsExactly(ActivityAction.BACKUP_STARTED, ActivityAction.BACKUP_FAILED);
        assertNoTempFilesLeft();

        mockMvc.perform(get("/api/admin/backups/" + id).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.errorMessage").value(containsString("password authentication failed")));
    }

    @Test
    void aTimedOutDump_isRecordedAsFailed() throws Exception {

        doThrow(new BackupFailedException("pg_dump timed out after 15 min and was stopped.")).when(dumper).dump(any());

        DbBackup b = awaitFinished(startBackup());

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).isEqualTo("pg_dump timed out after 15 min and was stopped.");
        verify(storage, never()).upload(anyString(), any());
    }

    @Test
    void anEmptyDumpFile_isRejected_beforeVerificationOrUpload() throws Exception {

        doAnswer(inv -> null).when(dumper).dump(any()); // leaves the 0-byte temp file

        DbBackup b = awaitFinished(startBackup());

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).isEqualTo("pg_dump produced an empty file.");
        verify(dumper, never()).verify(any());
        verify(storage, never()).upload(anyString(), any());
        assertNoTempFilesLeft();
    }

    @Test
    void anArchiveThatFailsVerification_isRejected_andNotUploaded() throws Exception {

        when(dumper.verify(any())).thenThrow(new BackupFailedException(
                "The backup file is incomplete or unreadable (pg_restore --list exit 1): could not read from input file"));

        DbBackup b = awaitFinished(startBackup());

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).contains("incomplete or unreadable");
        assertThat(b.getObjectKey()).isNull();
        verify(storage, never()).upload(anyString(), any());
    }

    @Test
    void aFailedUpload_isRecordedAsFailed_andThePartialObjectIsRemoved() throws Exception {

        doThrow(new IOException("Backup storage rejected the upload (HTTP 413)")).when(storage).upload(anyString(), any());

        DbBackup b = awaitFinished(startBackup());

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).isEqualTo(
                "Uploading the backup to private storage failed: Backup storage rejected the upload (HTTP 413).");
        verify(storage).delete(anyString());
        assertNoTempFilesLeft();
    }

    @Test
    void anUnexpectedError_neverExposesItsRawMessage() throws Exception {

        doThrow(new RuntimeException("jdbc:postgresql://secret-host:5432/db password=hunter2")).when(dumper).dump(any());

        DbBackup b = awaitFinished(startBackup());

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).isEqualTo("The backup failed unexpectedly (RuntimeException).");
    }

    @Test
    void withoutBackupStorage_theButtonReports503_andNothingStarts() throws Exception {

        when(storage.isConfigured()).thenReturn(false);

        mockMvc.perform(post("/api/admin/backups").header("Authorization", bearer(admin)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("not configured")));

        assertThat(backupRepository.count()).isZero();
        verify(dumper, never()).dump(any());
    }

    // ---- retention and recovery -------------------------------------------

    @Test
    void retention_keepsOnlyTheNewestSuccessfulBackups() throws Exception {

        long first = startBackup();
        awaitFinished(first);
        long second = startBackup();
        awaitFinished(second);
        reset(storage);
        when(storage.isConfigured()).thenReturn(true);
        long third = startBackup();
        awaitFinished(third);

        DbBackup oldest = backupRepository.findById(first).orElseThrow();
        assertThat(oldest.getRemovedAt()).isNotNull();
        verify(storage).delete(oldest.getObjectKey());
        assertThat(backupRepository.findById(second).orElseThrow().getRemovedAt()).isNull();
        assertThat(backupRepository.findById(third).orElseThrow().getRemovedAt()).isNull();

        mockMvc.perform(get("/api/admin/backups/" + first).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void aBackupLeftRunningByARestart_isMarkedFailedAtStartup() {

        DbBackup orphan = backupRepository.save(DbBackup.builder().status(BackupStatus.RUNNING)
                .initiatedByName("x").startedAt(LocalDateTime.now().minusMinutes(2)).build());

        backupService.failInterruptedBackups();

        DbBackup b = backupRepository.findById(orphan.getId()).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).contains("restarted");
    }

    @Test
    void aStaleRunningRow_doesNotBlockANewBackupForever() throws Exception {

        DbBackup stale = backupRepository.save(DbBackup.builder().status(BackupStatus.RUNNING)
                .initiatedByName("x").startedAt(LocalDateTime.now().minusHours(3)).build());

        long id = startBackup();

        assertThat(awaitFinished(id).getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        assertThat(backupRepository.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(BackupStatus.FAILED);
    }

    @Test
    void aRecentRunningRowFromAnotherInstance_blocksANewBackup() throws Exception {

        backupRepository.save(DbBackup.builder().status(BackupStatus.RUNNING)
                .initiatedByName("x").startedAt(LocalDateTime.now().minusMinutes(1)).build());

        mockMvc.perform(post("/api/admin/backups").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        assertThat(backupService.isRunning()).isFalse();
    }
}
