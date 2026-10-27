package com.compact.crm.repository;

import com.compact.crm.entity.EmailBulkBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailBulkBatchRepository extends JpaRepository<EmailBulkBatch, Long> {

    Optional<EmailBulkBatch> findByBatchId(String batchId);
}
