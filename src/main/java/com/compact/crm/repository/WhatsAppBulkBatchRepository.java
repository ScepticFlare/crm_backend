package com.compact.crm.repository;

import com.compact.crm.entity.WhatsAppBulkBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WhatsAppBulkBatchRepository extends JpaRepository<WhatsAppBulkBatch, Long> {

    Optional<WhatsAppBulkBatch> findByBatchId(String batchId);
}
