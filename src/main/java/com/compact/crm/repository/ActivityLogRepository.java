package com.compact.crm.repository;

import com.compact.crm.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

// JpaSpecificationExecutor backs both the combinable filter query (see
// specification.ActivityLogSpecifications) and the summary stats
// (targeted count()/findAll(..., PageRequest.of(0, 1, ...)) calls in
// service.ActivityLogService) - the same pattern
// LeadRepository/CustomerRepository/FollowUpRepository already use for
// their list endpoints.
@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long>, JpaSpecificationExecutor<ActivityLog> {
}
