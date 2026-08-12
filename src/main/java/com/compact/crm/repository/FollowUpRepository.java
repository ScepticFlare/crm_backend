package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    List<FollowUp> findByLeadId(Long leadId);

    List<FollowUp> findByOpportunityId(Long opportunityId);

    @Query("""
SELECT f
FROM FollowUp f
LEFT JOIN f.lead l
LEFT JOIN f.opportunity o
LEFT JOIN o.lead ol
WHERE
(
    :employee IS NULL
    OR l.assignedEmployee = :employee
    OR ol.assignedEmployee = :employee
)
AND
    f.scheduledDate >= :from
AND
    f.scheduledDate < :to
AND
(
    LOWER(COALESCE(f.remarks, '')) LIKE LOWER(CONCAT('%', :search, '%'))
    OR LOWER(f.activityType.name) LIKE LOWER(CONCAT('%', :search, '%'))
)
""")
    Page<FollowUp> searchFollowUps(
            @Param("employee") Employee employee,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

}