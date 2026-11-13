package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.LeadValidity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
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

    // Lead export: the Opportunity (with its stage) of each of these leads,
    // in one query per chunk - backs the `status-in` / `likely conversion`
    // columns without a lookup per row. See LeadService.findForWorkbookExport.
    @Query("SELECT o FROM Opportunity o LEFT JOIN FETCH o.salesStage WHERE o.lead.id IN :leadIds")
    List<Opportunity> findWithStageByLeadIds(@Param("leadIds") List<Long> leadIds);

    // Which of these leads already have an Opportunity (drives "converted").
    @Query("SELECT DISTINCT o.lead.id FROM Opportunity o WHERE o.lead.id IN :leadIds")
    List<Long> findLeadIdsWithOpportunity(@Param("leadIds") List<Long> leadIds);

    // ---- retirement of the NEW stage (service.NewStageRetirement) ----

    // [id, title] of every Opportunity in the given stage, row-locked (SELECT
    // ... FOR UPDATE) until the retirement's transaction ends.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o.id, o.title FROM Opportunity o WHERE o.salesStage.id = :stageId ORDER BY o.id")
    List<Object[]> findIdAndTitleBySalesStageId(@Param("stageId") Long stageId);

    // Moves every Opportunity of one stage to another and records the
    // provenance, in one statement. A bulk update on purpose: it changes only
    // these two columns - no entity callback runs, so updatedAt, and every
    // other field and relationship, is left exactly as it was.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Opportunity o
        SET o.salesStage = :target, o.migratedFromNewAt = :movedAt
        WHERE o.salesStage.id = :sourceStageId
    """)
    int moveFromRetiredNewStage(
            @Param("sourceStageId") Long sourceStageId,
            @Param("target") com.compact.crm.entity.SalesStage target,
            @Param("movedAt") LocalDateTime movedAt);

    // ---- retirement of the remaining non-main stages (service.LegacyStageRetirement) ----

    // [id, title, subStatus] of every Opportunity in the given stage, row-locked
    // (SELECT ... FOR UPDATE) until the retirement's transaction ends, so the
    // bulk move changes exactly these rows.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o.id, o.title, o.subStatus FROM Opportunity o WHERE o.salesStage.id = :stageId ORDER BY o.id")
    List<Object[]> findIdTitleAndSubStatusBySalesStageId(@Param("stageId") Long stageId);

    // Moves every Opportunity of a retired stage to a main stage, sets the
    // sub-status (null clears it - only IN PROGRESS has one) and records the
    // provenance, in one statement. A bulk update on purpose: only these
    // columns change - no entity callback runs, so updatedAt, and every other
    // field and relationship, is left exactly as it was.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Opportunity o
        SET o.salesStage = :target, o.subStatus = :subStatus,
            o.retiredStageType = :retiredType, o.retiredStageName = :retiredName,
            o.retiredStageMovedAt = :movedAt
        WHERE o.salesStage.id = :sourceStageId
    """)
    int moveFromRetiredStageSettingSubStatus(
            @Param("sourceStageId") Long sourceStageId,
            @Param("target") com.compact.crm.entity.SalesStage target,
            @Param("subStatus") com.compact.crm.enums.OpportunitySubStatus subStatus,
            @Param("retiredType") com.compact.crm.enums.SalesStageType retiredType,
            @Param("retiredName") String retiredName,
            @Param("movedAt") LocalDateTime movedAt);

    // As above, but leaves the stored sub-status as it is (a custom stage
    // moved to IN PROGRESS whose name names no sub-status).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Opportunity o
        SET o.salesStage = :target,
            o.retiredStageType = :retiredType, o.retiredStageName = :retiredName,
            o.retiredStageMovedAt = :movedAt
        WHERE o.salesStage.id = :sourceStageId
    """)
    int moveFromRetiredStageKeepingSubStatus(
            @Param("sourceStageId") Long sourceStageId,
            @Param("target") com.compact.crm.entity.SalesStage target,
            @Param("retiredType") com.compact.crm.enums.SalesStageType retiredType,
            @Param("retiredName") String retiredName,
            @Param("movedAt") LocalDateTime movedAt);

    // [id, title] of every Opportunity with no stage at all, row-locked.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o.id, o.title FROM Opportunity o WHERE o.salesStage IS NULL ORDER BY o.id")
    List<Object[]> findIdAndTitleWithoutSalesStage();

    // Moves every Opportunity with no stage to IN PROGRESS (bulk; nothing else
    // changes - no entity callback runs, so updatedAt is kept).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Opportunity o
        SET o.salesStage = :target, o.retiredStageMovedAt = :movedAt
        WHERE o.salesStage IS NULL
    """)
    int moveOpportunitiesWithoutStage(
            @Param("target") com.compact.crm.entity.SalesStage target,
            @Param("movedAt") LocalDateTime movedAt);
}
