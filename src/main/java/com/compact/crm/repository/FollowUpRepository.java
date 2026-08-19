package com.compact.crm.repository;

import com.compact.crm.entity.FollowUp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

// JpaSpecificationExecutor backs the combinable search/filter/sort list
// query - see service.FollowUpService.searchFollowUps +
// specification.FollowUpSpecifications, which replace the old
// hand-written boolean-flag "searchFollowUps" @Query that used to live here.
public interface FollowUpRepository extends JpaRepository<FollowUp, Long>, JpaSpecificationExecutor<FollowUp> {

    List<FollowUp> findByLeadId(Long leadId);

    List<FollowUp> findByOpportunityId(Long opportunityId);

    boolean existsByEmployeeId(Long employeeId);
}
