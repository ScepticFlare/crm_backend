package com.compact.crm.repository;

import com.compact.crm.entity.DbBackup;
import com.compact.crm.enums.BackupStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DbBackupRepository extends JpaRepository<DbBackup, Long> {

    Optional<DbBackup> findFirstByStatusOrderByStartedAtDesc(BackupStatus status);

    List<DbBackup> findByStatus(BackupStatus status);

    List<DbBackup> findTop20ByOrderByStartedAtDescIdDesc();

    // Retention candidates: every successful backup whose dump is still stored.
    List<DbBackup> findByStatusAndRemovedAtIsNullOrderByStartedAtDescIdDesc(BackupStatus status);
}
