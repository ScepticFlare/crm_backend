package com.compact.crm.repository;

import com.compact.crm.entity.WhatsAppTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplate, Long> {

    List<WhatsAppTemplate> findByIsActiveTrueOrderByNameAsc();

    List<WhatsAppTemplate> findAllByOrderByIsActiveDescNameAsc();
}
