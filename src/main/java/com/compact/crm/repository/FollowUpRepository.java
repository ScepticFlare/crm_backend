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
}
