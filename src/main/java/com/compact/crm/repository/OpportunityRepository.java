package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.LeadValidity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// JpaSpecificationExecutor backs the combinable search/filter/sort list
// query - see service.OpportunityService.searchOpportunities +
// specification.OpportunitySpecifications, which replace the old
// hand-written boolean-flag @Query methods that used to live here.
public interface OpportunityRepository extends JpaRepository<Opportunity, Long>, JpaSpecificationExecutor<Opportunity> {

    boolean existsByLeadId(Long leadId);

    // Export helpers: fetch-join the Opportunity's own line items for a chunk
    // of already-loaded rows, initializing those collections on the managed
    // entities in the same persistence context (results are not used
    // directly). One query per collection per chunk - see
    // OpportunityService.findForExport.
    @Query("""
        SELECT DISTINCT o FROM Opportunity o
        LEFT JOIN FETCH o.opportunityProducts op
        LEFT JOIN FETCH op.product
        WHERE o.id IN :ids
    """)
    List<Opportunity> fetchProducts(@Param("ids") List<Long> ids);

    @Query("""
        SELECT DISTINCT o FROM Opportunity o
        LEFT JOIN FETCH o.opportunityBatteries ob
        LEFT JOIN FETCH ob.battery
        WHERE o.id IN :ids
    """)
    List<Opportunity> fetchBatteries(@Param("ids") List<Long> ids);

    // Backs the Lead-delete cascade (LeadService.deleteLead/getDeleteImpact)
    // - a Lead has at most one Opportunity (enforced by existsByLeadId's
    // duplicate-conversion check in convertLeadToOpportunity).
    Optional<Opportunity> findByLeadId(Long leadId);

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

    // Rows are [stage name, stage type (nullable), sub-status (nullable), count].
    // Backs the Dashboard's pipeline-by-stage breakdown (GET
    // /api/dashboard/stats) - a GROUP BY COUNT instead of fetching every
    // Opportunity row just to tally them client-side (see
    // service.DashboardService).
    @Query("SELECT o.salesStage.name, o.salesStage.stageType, o.subStatus, COUNT(o) FROM Opportunity o GROUP BY o.salesStage.name, o.salesStage.stageType, o.subStatus")
    List<Object[]> countAllGroupedBySalesStage();

    @Query("""
        SELECT o.salesStage.name, o.salesStage.stageType, o.subStatus, COUNT(o)
        FROM Opportunity o
        WHERE o.lead.assignedEmployee.id IN :employeeIds
        GROUP BY o.salesStage.name, o.salesStage.stageType, o.subStatus
    """)
    List<Object[]> countGroupedBySalesStageForEmployees(@Param("employeeIds") List<Long> employeeIds);

    // Atomic "acknowledge": a no-op (0 rows) when this review date was
    // already acknowledged, so repeated or concurrent clicks neither change
    // anything twice nor produce duplicate history. Never touches the stage.
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Opportunity o
        SET o.postponedReminderAcknowledgedFor = :date, o.updatedAt = :now
        WHERE o.id = :id
        AND o.postponedReviewDate = :date
        AND (o.postponedReminderAcknowledgedFor IS NULL OR o.postponedReminderAcknowledgedFor <> :date)
    """)
    int acknowledgeReminder(
            @Param("id") Long id,
            @Param("date") java.time.LocalDate date,
            @Param("now") java.time.LocalDateTime now);

    // Which of these leads already have an Opportunity (drives "converted").
    @Query("SELECT DISTINCT o.lead.id FROM Opportunity o WHERE o.lead.id IN :leadIds")
    List<Long> findLeadIdsWithOpportunity(@Param("leadIds") List<Long> leadIds);
}
