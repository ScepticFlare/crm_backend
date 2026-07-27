package com.compact.crm.repository;

import com.compact.crm.entity.LeadSourceMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeadSourceMasterRepository extends JpaRepository<LeadSourceMaster, Long> {

    Optional<LeadSourceMaster> findByNameIgnoreCase(String name);

}