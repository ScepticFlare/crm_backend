package com.compact.crm.repository;

import com.compact.crm.dto.response.UpcomingFollowUpResponse;
import com.compact.crm.entity.FollowUp;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

// JpaSpecificationExecutor backs the combinable search/filter/sort list
// query - see service.FollowUpService.searchFollowUps +
// specification.FollowUpSpecifications, which replace the old
// hand-written boolean-flag "searchFollowUps" @Query that used to live here.
public interface FollowUpRepository extends JpaRepository<FollowUp, Long>, JpaSpecificationExecutor<FollowUp> {

    List<FollowUp> findByLeadId(Long leadId);

    List<FollowUp> findByOpportunityId(Long opportunityId);

    boolean existsByEmployeeId(Long employeeId);

    // Backs the Dashboard's "Upcoming Follow Ups" widget (see service.
    // DashboardService) - a flat constructor projection, not the full
    // FollowUp entity graph. companyName prefers the follow-up's own lead,
    // falling back to its opportunity's lead - same fallback the Dashboard
    // used to do client-side.
    @Query("""
        SELECT new com.compact.crm.dto.response.UpcomingFollowUpResponse(
            f.id, f.scheduledDate, f.status,
            COALESCE(l.id, ol.id), COALESCE(l.companyName, ol.companyName),
            o.id, at.name)
        FROM FollowUp f
        LEFT JOIN f.lead l
        LEFT JOIN f.opportunity o
        LEFT JOIN o.lead ol
        LEFT JOIN f.activityType at
        WHERE f.status = com.compact.crm.enums.FollowUpStatus.PENDING
        AND f.scheduledDate >= :from
        ORDER BY f.scheduledDate ASC
    """)
    List<UpcomingFollowUpResponse> findUpcoming(@Param("from") LocalDateTime from, Pageable pageable);

    @Query("""
        SELECT new com.compact.crm.dto.response.UpcomingFollowUpResponse(
            f.id, f.scheduledDate, f.status,
            COALESCE(l.id, ol.id), COALESCE(l.companyName, ol.companyName),
            o.id, at.name)
        FROM FollowUp f
        LEFT JOIN f.lead l
        LEFT JOIN f.opportunity o
        LEFT JOIN o.lead ol
        LEFT JOIN f.activityType at
        WHERE f.status = com.compact.crm.enums.FollowUpStatus.PENDING
        AND f.scheduledDate >= :from
        AND (l.assignedEmployee.id IN :employeeIds OR ol.assignedEmployee.id IN :employeeIds)
        ORDER BY f.scheduledDate ASC
    """)
    List<UpcomingFollowUpResponse> findUpcomingForEmployees(
            @Param("from") LocalDateTime from,
            @Param("employeeIds") List<Long> employeeIds,
            Pageable pageable);

    // "Next action" detection for the Opportunity workflow warnings: ids of
    // Opportunities (or originating Leads) that have at least one PENDING
    // follow-up scheduled from `from` onward - the same PENDING + upcoming
    // definition the Dashboard's Upcoming Follow Ups widget uses.
    // Rows are [opportunity id, remarks]. The remarks ("next action") are
    // checked in Java by service.FollowUpRules so the rule has ONE definition
    // and does not depend on how the database handles functions over TEXT.
    @Query("""
        SELECT f.opportunity.id, f.remarks FROM FollowUp f
        WHERE f.opportunity.id IN :opportunityIds
        AND f.status = com.compact.crm.enums.FollowUpStatus.PENDING
        AND f.scheduledDate >= :from
    """)
    List<Object[]> findPendingFutureByOpportunityIds(
            @Param("opportunityIds") List<Long> opportunityIds,
            @Param("from") LocalDateTime from);

    // Rows are [lead id, remarks].
    @Query("""
        SELECT f.lead.id, f.remarks FROM FollowUp f
        WHERE f.lead.id IN :leadIds
        AND f.status = com.compact.crm.enums.FollowUpStatus.PENDING
        AND f.scheduledDate >= :from
    """)
    List<Object[]> findPendingFutureByLeadIds(
            @Param("leadIds") List<Long> leadIds,
            @Param("from") LocalDateTime from);

    // Which of these leads have at least one COMPLETED follow-up (i.e. the
    // lead has actually been contacted) - drives "contact overdue".
    @Query("""
        SELECT DISTINCT f.lead.id FROM FollowUp f
        WHERE f.lead.id IN :leadIds
        AND f.status = com.compact.crm.enums.FollowUpStatus.COMPLETED
    """)
    List<Long> findLeadIdsWithCompletedFollowUp(@Param("leadIds") List<Long> leadIds);

    // Which of these leads have a follow-up created or updated since `cutoff`
    // - recent genuine activity that keeps a lead from being "stale".
    @Query("""
        SELECT DISTINCT f.lead.id FROM FollowUp f
        WHERE f.lead.id IN :leadIds
        AND (f.updatedAt >= :cutoff OR f.createdAt >= :cutoff)
    """)
    List<Long> findLeadIdsWithFollowUpSince(
            @Param("leadIds") List<Long> leadIds,
            @Param("cutoff") LocalDateTime cutoff);
}
