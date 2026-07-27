package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    List<FollowUp> findByLeadId(Long leadId);

    List<FollowUp> findByOpportunityId(Long opportunityId);

    @Query("""
        SELECT f
        FROM FollowUp f
        WHERE
            (
                :employee IS NULL
                OR f.lead.assignedEmployee = :employee
                OR f.opportunity.lead.assignedEmployee = :employee
            )
        AND
        (
            LOWER(f.remarks) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(f.activityType.name) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<FollowUp> searchFollowUps(
            @Param("employee") Employee employee,
            @Param("search") String search,
            Pageable pageable
    );

}