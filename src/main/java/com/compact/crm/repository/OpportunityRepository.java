package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Opportunity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long> {

    boolean existsByLeadId(Long leadId);

    @Query("""
        SELECT o
        FROM Opportunity o
        WHERE
            (:employee IS NULL OR o.lead.assignedEmployee = :employee)
        AND
        (
            LOWER(o.title) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.lead.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.lead.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.salesStage.name) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<Opportunity> searchOpportunities(
            @Param("employee") Employee employee,
            @Param("search") String search,
            Pageable pageable
    );

}