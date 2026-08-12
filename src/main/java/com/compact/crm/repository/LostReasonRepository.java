package com.compact.crm.repository;

import com.compact.crm.entity.LostReason;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LostReasonRepository extends JpaRepository<LostReason, Long> {

    Optional<LostReason> findByNameIgnoreCase(String name);

}