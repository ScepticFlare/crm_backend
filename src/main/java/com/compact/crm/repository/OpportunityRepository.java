package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.LeadValidity;
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
            o.createdAt >= :from
        AND
            o.createdAt < :to
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
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    @Query("""
        SELECT o
        FROM Opportunity o
        WHERE
            (:employee IS NULL OR o.lead.assignedEmployee = :employee)
        AND
            o.salesStage.name NOT IN ('WON', 'LOST', 'POSTPONED', 'DROPPED', 'UNRESPONSIVE')
        AND
            o.createdAt >= :from
        AND
            o.createdAt < :to
        AND
        (
            LOWER(o.title) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.lead.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.lead.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(o.salesStage.name) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<Opportunity> searchInProgressOpportunities(
            @Param("employee") Employee employee,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
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

    @Query("""
        SELECT DISTINCT o
        FROM Opportunity o
        LEFT JOIN FETCH o.salesStage
        LEFT JOIN FETCH o.lead l
        LEFT JOIN FETCH l.industry
        LEFT JOIN FETCH l.leadSource
        LEFT JOIN FETCH l.assignedEmployee
        LEFT JOIN FETCH l.leadProducts lp
        LEFT JOIN FETCH lp.product
        WHERE o.createdAt BETWEEN :from AND :to
        AND (:employee IS NULL OR l.assignedEmployee = :employee)
        AND (:leadSourceId IS NULL OR l.leadSource.id = :leadSourceId)
        AND (:industryId IS NULL OR l.industry.id = :industryId)
        AND (:salesStageId IS NULL OR o.salesStage.id = :salesStageId)
        AND (:leadValidity IS NULL OR o.leadValidity = :leadValidity)
        AND (:productId IS NULL OR EXISTS (
            SELECT 1 FROM LeadProduct lp2
            WHERE lp2.lead = l AND lp2.product.id = :productId
        ))
        AND (:batteryId IS NULL OR EXISTS (
            SELECT 1 FROM LeadBattery lb2
            WHERE lb2.lead = l AND lb2.battery.id = :batteryId
        ))
    """)
    List<Opportunity> findForFullReportWithProducts(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("employee") Employee employee,
            @Param("leadSourceId") Long leadSourceId,
            @Param("industryId") Long industryId,
            @Param("salesStageId") Long salesStageId,
            @Param("leadValidity") LeadValidity leadValidity,
            @Param("productId") Long productId,
            @Param("batteryId") Long batteryId
    );

    @Query("""
        SELECT DISTINCT o
        FROM Opportunity o
        LEFT JOIN FETCH o.lead l
        LEFT JOIN FETCH l.leadBatteries lb
        LEFT JOIN FETCH lb.battery
        WHERE o.createdAt BETWEEN :from AND :to
        AND (:employee IS NULL OR l.assignedEmployee = :employee)
        AND (:leadSourceId IS NULL OR l.leadSource.id = :leadSourceId)
        AND (:industryId IS NULL OR l.industry.id = :industryId)
        AND (:salesStageId IS NULL OR o.salesStage.id = :salesStageId)
        AND (:leadValidity IS NULL OR o.leadValidity = :leadValidity)
        AND (:productId IS NULL OR EXISTS (
            SELECT 1 FROM LeadProduct lp2
            WHERE lp2.lead = l AND lp2.product.id = :productId
        ))
        AND (:batteryId IS NULL OR EXISTS (
            SELECT 1 FROM LeadBattery lb2
            WHERE lb2.lead = l AND lb2.battery.id = :batteryId
        ))
    """)
    List<Opportunity> findForFullReportWithBatteries(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("employee") Employee employee,
            @Param("leadSourceId") Long leadSourceId,
            @Param("industryId") Long industryId,
            @Param("salesStageId") Long salesStageId,
            @Param("leadValidity") LeadValidity leadValidity,
            @Param("productId") Long productId,
            @Param("batteryId") Long batteryId
    );
}