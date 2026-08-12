package com.compact.crm.repository;

import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Opportunity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByOpportunity(Opportunity opportunity);

    @Query("""
        SELECT c
        FROM Customer c
        WHERE
            (:employee IS NULL OR c.opportunity.lead.assignedEmployee = :employee)
        AND
            c.createdAt >= :from
        AND
            c.createdAt < :to
        AND
        (
            LOWER(c.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<Customer> searchCustomers(
            @Param("employee") Employee employee,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    @Query("""
        SELECT DISTINCT c
        FROM Customer c
        LEFT JOIN FETCH c.assignedEmployee
        LEFT JOIN FETCH c.opportunity op
        LEFT JOIN FETCH op.salesStage
        LEFT JOIN FETCH op.lead l
        LEFT JOIN FETCH l.industry
        LEFT JOIN FETCH l.leadSource
        LEFT JOIN FETCH l.leadProducts lp
        LEFT JOIN FETCH lp.product
        WHERE c.createdAt BETWEEN :from AND :to
        AND (:employee IS NULL OR c.assignedEmployee = :employee)
        AND (:leadSourceId IS NULL OR l.leadSource.id = :leadSourceId)
        AND (:industryId IS NULL OR l.industry.id = :industryId)
        AND (:salesStageId IS NULL OR op.salesStage.id = :salesStageId)
        AND (:productId IS NULL OR EXISTS (
            SELECT 1 FROM LeadProduct lp2
            WHERE lp2.lead = l AND lp2.product.id = :productId
        ))
        AND (:batteryId IS NULL OR EXISTS (
            SELECT 1 FROM LeadBattery lb2
            WHERE lb2.lead = l AND lb2.battery.id = :batteryId
        ))
    """)
    List<Customer> findForFullReportWithProducts(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("employee") Employee employee,
            @Param("leadSourceId") Long leadSourceId,
            @Param("industryId") Long industryId,
            @Param("salesStageId") Long salesStageId,
            @Param("productId") Long productId,
            @Param("batteryId") Long batteryId
    );

    @Query("""
        SELECT DISTINCT c
        FROM Customer c
        LEFT JOIN FETCH c.opportunity op
        LEFT JOIN FETCH op.lead l
        LEFT JOIN FETCH l.leadBatteries lb
        LEFT JOIN FETCH lb.battery
        WHERE c.createdAt BETWEEN :from AND :to
        AND (:employee IS NULL OR c.assignedEmployee = :employee)
        AND (:leadSourceId IS NULL OR l.leadSource.id = :leadSourceId)
        AND (:industryId IS NULL OR l.industry.id = :industryId)
        AND (:salesStageId IS NULL OR op.salesStage.id = :salesStageId)
        AND (:productId IS NULL OR EXISTS (
            SELECT 1 FROM LeadProduct lp2
            WHERE lp2.lead = l AND lp2.product.id = :productId
        ))
        AND (:batteryId IS NULL OR EXISTS (
            SELECT 1 FROM LeadBattery lb2
            WHERE lb2.lead = l AND lb2.battery.id = :batteryId
        ))
    """)
    List<Customer> findForFullReportWithBatteries(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("employee") Employee employee,
            @Param("leadSourceId") Long leadSourceId,
            @Param("industryId") Long industryId,
            @Param("salesStageId") Long salesStageId,
            @Param("productId") Long productId,
            @Param("batteryId") Long batteryId
    );

}