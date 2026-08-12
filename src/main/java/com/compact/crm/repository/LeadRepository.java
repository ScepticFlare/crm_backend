package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
public interface LeadRepository extends JpaRepository<Lead, Long> {

    @Query("""
        SELECT l
        FROM Lead l
        WHERE
            (:employee IS NULL OR l.assignedEmployee = :employee)
        AND
            l.createdAt >= :from
        AND
            l.createdAt < :to
        AND
        (
            LOWER(l.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.phone) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.email) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<Lead> searchLeads(
            @Param("employee") Employee employee,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    @Query("""
        SELECT l
        FROM Lead l
        WHERE
            (:employee IS NULL OR l.assignedEmployee = :employee)
        AND
            l.leadStatus = :status
        AND
            l.createdAt >= :from
        AND
            l.createdAt < :to
        AND
        (
            LOWER(l.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.phone) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.email) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<Lead> searchLeadsByStatus(
            @Param("employee") Employee employee,
            @Param("status") LeadStatus status,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);
    List<Lead> findByCreatedAtBetween(
            LocalDateTime from,
            LocalDateTime to
    );

    List<Lead> findByAssignedEmployeeAndCreatedAtBetween(
            Employee employee,
            LocalDateTime from,
            LocalDateTime to
    );

    @Query("""
        SELECT DISTINCT l
        FROM Lead l
        LEFT JOIN FETCH l.industry
        LEFT JOIN FETCH l.leadSource
        LEFT JOIN FETCH l.assignedEmployee
        LEFT JOIN FETCH l.leadProducts lp
        LEFT JOIN FETCH lp.product
        WHERE l.createdAt BETWEEN :from AND :to
        AND (:employee IS NULL OR l.assignedEmployee = :employee)
        AND (:leadSourceId IS NULL OR l.leadSource.id = :leadSourceId)
        AND (:industryId IS NULL OR l.industry.id = :industryId)
        AND (:leadValidity IS NULL OR l.leadValidity = :leadValidity)
        AND (:productId IS NULL OR EXISTS (
            SELECT 1 FROM LeadProduct lp2
            WHERE lp2.lead = l AND lp2.product.id = :productId
        ))
        AND (:batteryId IS NULL OR EXISTS (
            SELECT 1 FROM LeadBattery lb2
            WHERE lb2.lead = l AND lb2.battery.id = :batteryId
        ))
    """)
    List<Lead> findForFullReportWithProducts(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("employee") Employee employee,
            @Param("leadSourceId") Long leadSourceId,
            @Param("industryId") Long industryId,
            @Param("leadValidity") LeadValidity leadValidity,
            @Param("productId") Long productId,
            @Param("batteryId") Long batteryId
    );

    @Query("""
        SELECT DISTINCT l
        FROM Lead l
        LEFT JOIN FETCH l.leadBatteries lb
        LEFT JOIN FETCH lb.battery
        WHERE l.createdAt BETWEEN :from AND :to
        AND (:employee IS NULL OR l.assignedEmployee = :employee)
        AND (:leadSourceId IS NULL OR l.leadSource.id = :leadSourceId)
        AND (:industryId IS NULL OR l.industry.id = :industryId)
        AND (:leadValidity IS NULL OR l.leadValidity = :leadValidity)
        AND (:productId IS NULL OR EXISTS (
            SELECT 1 FROM LeadProduct lp2
            WHERE lp2.lead = l AND lp2.product.id = :productId
        ))
        AND (:batteryId IS NULL OR EXISTS (
            SELECT 1 FROM LeadBattery lb2
            WHERE lb2.lead = l AND lb2.battery.id = :batteryId
        ))
    """)
    List<Lead> findForFullReportWithBatteries(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("employee") Employee employee,
            @Param("leadSourceId") Long leadSourceId,
            @Param("industryId") Long industryId,
            @Param("leadValidity") LeadValidity leadValidity,
            @Param("productId") Long productId,
            @Param("batteryId") Long batteryId
    );

}
