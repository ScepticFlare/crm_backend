package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

// JpaSpecificationExecutor backs the combinable search/filter/sort list
// query (see service.LeadService.searchLeads + specification.LeadSpecifications) -
// it replaces the old hand-written boolean-flag "searchLeads"/
// "searchLeadsByStatus" @Query methods that used to live here.
public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    boolean existsByAssignedEmployeeId(Long employeeId);
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

    // Backs scheduler.StaleLeadScheduler: candidates for automatic
    // deactivation are leads still in an open/active status (not already
    // INACTIVE or INVALID, and not a resolved outcome like WON/LOST/DROPPED/
    // UNRESPONSIVE), not yet converted to an Opportunity (that pipeline
    // governs its own follow-up from that point on), whose own last edit
    // (updatedAt) predates the cutoff AND that have no FollowUp created or
    // updated since the cutoff either - "touched" means a real edit to the
    // Lead itself or genuine follow-up activity, never just being viewed
    // (viewing never writes updatedAt or a FollowUp row).
    @Query("""
        SELECT l FROM Lead l
        WHERE l.leadStatus IN :activeStatuses
        AND l.updatedAt < :cutoff
        AND NOT EXISTS (
            SELECT 1 FROM FollowUp f
            WHERE f.lead = l
            AND (f.updatedAt >= :cutoff OR f.createdAt >= :cutoff)
        )
        AND NOT EXISTS (
            SELECT 1 FROM Opportunity o WHERE o.lead = l
        )
    """)
    List<Lead> findStaleActiveLeads(
            @Param("activeStatuses") List<LeadStatus> activeStatuses,
            @Param("cutoff") LocalDateTime cutoff
    );

}
