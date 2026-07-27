package com.compact.crm.repository;

import com.compact.crm.entity.ActivityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActivityTypeRepository extends JpaRepository<ActivityType, Long> {

    Optional<ActivityType> findByNameIgnoreCase(String name);

}