package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Opportunity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long> {

    boolean existsByLeadId(Long leadId);

    @Query("""
        SELECT o
        FROM Opportunity o
        WHERE
            (:employee IS NULL OR o.lead.assignedEmployee = :employee)
        AND
            (:stageName IS NULL OR o.salesStage.name = :stageName)
        AND
        (
            LOWER(o.title) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.lead.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.lead.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.salesStage.name) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<Opportunity> searchOpportunitiesByStage(
            @Param("employee") Employee employee,
            @Param("stageName") String stageName,
            @Param("search") String search,
            Pageable pageable
    );

    List<Opportunity> findByCreatedAtBetween(
            LocalDateTime from,
            LocalDateTime to
    );

    List<Opportunity> findByLeadAssignedEmployeeAndCreatedAtBetween(
            Employee employee,
            LocalDateTime from,
            LocalDateTime to
    );
}