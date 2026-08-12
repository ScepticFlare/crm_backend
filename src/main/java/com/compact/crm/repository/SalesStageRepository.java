package com.compact.crm.repository;

import com.compact.crm.entity.SalesStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesStageRepository extends JpaRepository<SalesStage, Long> {

    Optional<SalesStage> findByNameIgnoreCase(String name);

    List<SalesStage> findByIsActiveTrue();

}