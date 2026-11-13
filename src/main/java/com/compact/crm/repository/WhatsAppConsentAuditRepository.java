package com.compact.crm.repository;

import com.compact.crm.entity.WhatsAppConsentAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WhatsAppConsentAuditRepository extends JpaRepository<WhatsAppConsentAudit, Long> {

    Page<WhatsAppConsentAudit> findAllByOrderByPerformedAtDescIdDesc(Pageable pageable);

    List<WhatsAppConsentAudit> findByPhoneE164OrderByPerformedAtDescIdDesc(String phoneE164);
}
