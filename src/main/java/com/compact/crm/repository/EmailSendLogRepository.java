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

    List<EmailSendLog> findByBatchIdAndRecipientEmailAndStatusIn(
            String batchId, String recipientEmail, Collection<EmailSendStatus> statuses);

    // ---- cross-batch duplicate protection --------------------------------
    // "Active" statuses (ACCEPTED / UNKNOWN / PENDING) recorded by ANOTHER batch
    // since :since. A PENDING claim counts too: it is either being sent right now
    // or is a stale claim that reads as UNKNOWN - both mean "may already be sent".

    @Query("select distinct l.recipientEmail from EmailSendLog l "
            + "where l.recipientEmail in :emails and l.status in :statuses "
            + "and l.updatedAt >= :since and l.batchId <> :batchId")
    List<String> findRecentlyActiveEmails(@Param("emails") Collection<String> emails,
                                          @Param("statuses") Collection<EmailSendStatus> statuses,
                                          @Param("since") LocalDateTime since,
                                          @Param("batchId") String batchId);

    @Query("select distinct l.targetId from EmailSendLog l "
            + "where l.target = :target and l.targetId in :ids and l.status in :statuses "
            + "and l.updatedAt >= :since and l.batchId <> :batchId")
    List<Long> findRecentlyActiveRecordIds(@Param("target") BulkEmailTarget target,
                                           @Param("ids") Collection<Long> ids,
                                           @Param("statuses") Collection<EmailSendStatus> statuses,
                                           @Param("since") LocalDateTime since,
                                           @Param("batchId") String batchId);

    // Tie-break for two requests (other batches, or other records of the same
    // batch) claiming the same address at the same instant: the claim with the
    // lowest id wins, any later one backs off.
    @Query("select count(l) from EmailSendLog l "
            + "where l.recipientEmail = :email and l.id < :id "
            + "and l.status in :statuses and l.updatedAt >= :since")
    long countEarlierActiveClaims(@Param("email") String email,
                                  @Param("id") Long id,
                                  @Param("statuses") Collection<EmailSendStatus> statuses,
                                  @Param("since") LocalDateTime since);

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
