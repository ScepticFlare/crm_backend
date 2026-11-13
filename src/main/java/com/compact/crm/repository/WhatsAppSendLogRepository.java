package com.compact.crm.repository;

import com.compact.crm.entity.WhatsAppSendLog;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.WhatsAppDeliveryStatus;
import com.compact.crm.enums.WhatsAppSendStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Same queries as EmailSendLogRepository, keyed by the normalized phone number,
// plus the delivery-status updates driven by provider webhooks.
public interface WhatsAppSendLogRepository extends JpaRepository<WhatsAppSendLog, Long> {

    Optional<WhatsAppSendLog> findByBatchIdAndTargetAndTargetId(String batchId, BulkEmailTarget target, Long targetId);

    List<WhatsAppSendLog> findByBatchIdOrderByIdAsc(String batchId);

    List<WhatsAppSendLog> findByBatchIdAndPhoneE164AndStatusIn(
            String batchId, String phoneE164, Collection<WhatsAppSendStatus> statuses);

    Optional<WhatsAppSendLog> findByProviderMessageId(String providerMessageId);

    // ---- cross-batch duplicate protection (see BulkWhatsAppService) ------
    // "Active" statuses (ACCEPTED / UNKNOWN / PENDING) recorded by ANOTHER
    // batch since :since - each means "may already have been messaged".

    @Query("select distinct l.phoneE164 from WhatsAppSendLog l "
            + "where l.phoneE164 in :phones and l.status in :statuses "
            + "and l.updatedAt >= :since and l.batchId <> :batchId")
    List<String> findRecentlyActivePhones(@Param("phones") Collection<String> phones,
                                          @Param("statuses") Collection<WhatsAppSendStatus> statuses,
                                          @Param("since") LocalDateTime since,
                                          @Param("batchId") String batchId);

    @Query("select distinct l.targetId from WhatsAppSendLog l "
            + "where l.target = :target and l.targetId in :ids and l.status in :statuses "
            + "and l.updatedAt >= :since and l.batchId <> :batchId")
    List<Long> findRecentlyActiveRecordIds(@Param("target") BulkEmailTarget target,
                                           @Param("ids") Collection<Long> ids,
                                           @Param("statuses") Collection<WhatsAppSendStatus> statuses,
                                           @Param("since") LocalDateTime since,
                                           @Param("batchId") String batchId);

    // Two claims on the same number at the same instant: the lowest id wins.
    @Query("select count(l) from WhatsAppSendLog l "
            + "where l.phoneE164 = :phone and l.id < :id "
            + "and l.status in :statuses and l.updatedAt >= :since")
    long countEarlierActiveClaims(@Param("phone") String phone,
                                  @Param("id") Long id,
                                  @Param("statuses") Collection<WhatsAppSendStatus> statuses,
                                  @Param("since") LocalDateTime since);

    // Atomic compare-and-set: only one concurrent request can win the right to
    // (re)send a recipient. Returns 1 only for the winner.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update WhatsAppSendLog l set l.status = :to, l.detail = :detail, l.updatedAt = :now "
            + "where l.id = :id and l.status = :from")
    int transition(@Param("id") Long id,
                   @Param("from") WhatsAppSendStatus from,
                   @Param("to") WhatsAppSendStatus to,
                   @Param("detail") String detail,
                   @Param("now") LocalDateTime now);

    // Monotonic delivery update: applied only when the current delivery status
    // is unset or ranks lower (`lower` = every status ranking below `to`).
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update WhatsAppSendLog l set l.deliveryStatus = :to, l.deliveryUpdatedAt = :now "
            + "where l.providerMessageId = :messageId "
            + "and (l.deliveryStatus is null or l.deliveryStatus in :lower)")
    int advanceDelivery(@Param("messageId") String messageId,
                        @Param("to") WhatsAppDeliveryStatus to,
                        @Param("lower") Collection<WhatsAppDeliveryStatus> lower,
                        @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update WhatsAppSendLog l set l.deliveryStatus = :to, l.deliveryUpdatedAt = :now "
            + "where l.providerMessageId = :messageId and l.deliveryStatus is null")
    int advanceDeliveryFromUnset(@Param("messageId") String messageId,
                                 @Param("to") WhatsAppDeliveryStatus to,
                                 @Param("now") LocalDateTime now);
}
