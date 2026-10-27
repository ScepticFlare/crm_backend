package com.compact.crm.repository;

import com.compact.crm.entity.EmailSuppressionAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSuppressionAuditRepository extends JpaRepository<EmailSuppressionAudit, Long> {

    Page<EmailSuppressionAudit> findAllByOrderByIdDesc(Pageable pageable);
}
