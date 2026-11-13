package com.compact.crm.repository;

import com.compact.crm.entity.WhatsAppConsent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WhatsAppConsentRepository extends JpaRepository<WhatsAppConsent, Long> {

    Optional<WhatsAppConsent> findByPhoneE164(String phoneE164);

    List<WhatsAppConsent> findByPhoneE164In(Collection<String> phones);

    Page<WhatsAppConsent> findByPhoneE164ContainingOrderByUpdatedAtDesc(String fragment, Pageable pageable);

    Page<WhatsAppConsent> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
