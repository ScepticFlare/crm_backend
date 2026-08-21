package com.compact.crm.repository;

import com.compact.crm.entity.EmailTemplate;
import com.compact.crm.enums.EmailTemplateType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {

    // Backs the composer's template picker - only active templates of the
    // relevant type (Product Brochure vs Keep in Touch) are offered.
    List<EmailTemplate> findByTypeAndIsActiveTrueOrderByNameAsc(EmailTemplateType type);

    List<EmailTemplate> findByIsActiveTrueOrderByNameAsc();

    // The composer's initial prefill before the user has picked anything -
    // see entity.EmailTemplate.isDefault.
    Optional<EmailTemplate> findByTypeAndIsDefaultTrueAndIsActiveTrue(EmailTemplateType type);

    // Used by EmailTemplateService when (re)designating a default, to unset
    // whichever template previously held it for the same type.
    List<EmailTemplate> findByTypeAndIsDefaultTrue(EmailTemplateType type);

}
