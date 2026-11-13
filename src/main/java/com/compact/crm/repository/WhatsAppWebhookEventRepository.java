package com.compact.crm.repository;

import com.compact.crm.entity.WhatsAppWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface WhatsAppWebhookEventRepository extends JpaRepository<WhatsAppWebhookEvent, Long> {

    boolean existsByEventKey(String eventKey);

    List<WhatsAppWebhookEvent> findByProviderMessageIdAndAppliedFalse(String providerMessageId);

    List<WhatsAppWebhookEvent> findByProviderMessageIdInAndAppliedFalse(Collection<String> providerMessageIds);
}
