package com.compact.crm.repository;

import com.compact.crm.entity.EmailSuppression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, Long> {

    Optional<EmailSuppression> findByEmailNormalized(String emailNormalized);

    List<EmailSuppression> findByEmailNormalizedIn(Collection<String> emails);

    Page<EmailSuppression> findByEmailNormalizedContaining(String fragment, Pageable pageable);
}
