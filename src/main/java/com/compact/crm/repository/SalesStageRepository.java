package com.compact.crm.repository;

import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.SalesStageType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesStageRepository extends JpaRepository<SalesStage, Long> {

    Optional<SalesStage> findByNameIgnoreCase(String name);

    List<SalesStage> findByIsActiveTrue();

    // Core stage lookup by stable type (never by display name).
    List<SalesStage> findByStageType(SalesStageType stageType);

    List<SalesStage> findByStageTypeIsNull();

}
