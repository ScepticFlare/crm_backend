package com.compact.crm.repository;

import com.compact.crm.entity.EmailSendLog;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.EmailSendStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmailSendLogRepository extends JpaRepository<EmailSendLog, Long> {

    Optional<EmailSendLog> findByBatchIdAndTargetAndTargetId(String batchId, BulkEmailTarget target, Long targetId);

    List<EmailSendLog> findByBatchIdOrderByIdAsc(String batchId);

    boolean existsByBatchIdAndSentByEmployeeIdNot(String batchId, Long sentByEmployeeId);

    List<EmailSendLog> findByBatchIdAndRecipientEmailAndStatusIn(
            String batchId, String recipientEmail, Collection<EmailSendStatus> statuses);

    // Atomic compare-and-set so two concurrent submissions cannot both win
    // the right to (re)send the same recipient. Returns 1 only for the winner.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update EmailSendLog l set l.status = :to, l.detail = :detail, l.updatedAt = :now "
            + "where l.id = :id and l.status = :from")
    int transition(@Param("id") Long id,
                   @Param("from") EmailSendStatus from,
                   @Param("to") EmailSendStatus to,
                   @Param("detail") String detail,
                   @Param("now") LocalDateTime now);
}
