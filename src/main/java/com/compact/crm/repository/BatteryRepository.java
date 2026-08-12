package com.compact.crm.repository;

import com.compact.crm.entity.Battery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BatteryRepository extends JpaRepository<Battery, Long> {

    Optional<Battery> findByNameIgnoreCase(String name);

    List<Battery> findByIsActiveTrue();

}
