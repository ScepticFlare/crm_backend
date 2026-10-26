package com.compact.crm.service;

import com.compact.crm.dto.response.DashboardStatsResponse;
import com.compact.crm.dto.response.RecentLeadResponse;
import com.compact.crm.dto.response.UpcomingFollowUpResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.specification.CustomerSpecifications;
import com.compact.crm.specification.LeadSpecifications;
import com.compact.crm.specification.OpportunitySpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.compact.crm.security.AccessControlService.CUSTOMER_VIEW;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_VIEW;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;

// Backs GET /api/dashboard/stats. Before this endpoint existed, the
// Dashboard computed everything below by fetching a full paginated page
// (50 rows, full entity graphs) each of leads/customers/opportunities/
// followups and either reading .totalElements or eyeballing the in-memory
// page - four of the app's most expensive queries, run on every dashboard
// load, purely to produce a handful of counts and two 5-row widgets. Every
// number here is instead a COUNT/GROUP BY or a small LIMIT-ed constructor
// projection, scoped by the same RBAC visibility rules each module's own
// list endpoint already applies.
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final LeadRepository leadRepository;
    private final CustomerRepository customerRepository;
    private final OpportunityRepository opportunityRepository;
    private final FollowUpRepository followUpRepository;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;

    private static final List<String> PIPELINE_BUCKETS =
            List.of("NEW", "IN_PROGRESS", "POSTPONED", "WON", "LOST", "OTHER");

    public DashboardStatsResponse getStats() {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        List<Long> leadEmployeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, LEAD_VIEW);
        List<Long> customerEmployeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, CUSTOMER_VIEW);
        List<Long> opportunityEmployeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, OPPORTUNITY_VIEW);
        List<Long> followUpEmployeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, FOLLOWUP_VIEW);

        // Active leads only (NEW + VALID not yet converted) - the same set the
        // Leads page lists, so the card and the page it links to agree.
        // Invalid and converted leads are counted in the lead category report.
        long leadCount = leadRepository.count(com.compact.crm.specification.SpecificationUtil.and(java.util.Arrays.asList(
                LeadSpecifications.ownerIn(leadEmployeeIds),
                LeadSpecifications.inCategories(com.compact.crm.enums.LeadCategory.ACTIVE))));
        long customerCount = customerRepository.count(CustomerSpecifications.ownerIn(customerEmployeeIds));
        long opportunityCount = opportunityRepository.count(OpportunitySpecifications.ownerIn(opportunityEmployeeIds));
        long followUpCount = followUpRepository.count(com.compact.crm.specification.FollowUpSpecifications.ownerIn(followUpEmployeeIds));

        DashboardStatsResponse.PipelineCounts pipelineCounts = buildPipeline(opportunityEmployeeIds);

        Pageable top5CreatedDesc = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<RecentLeadResponse> recentLeads = leadEmployeeIds == null
                ? leadRepository.findRecent(top5CreatedDesc)
                : leadRepository.findRecentForEmployees(leadEmployeeIds, top5CreatedDesc);

        LocalDateTime today = LocalDate.now().atStartOfDay();
        Pageable top5ScheduledAsc = PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "scheduledDate"));
        List<UpcomingFollowUpResponse> upcomingFollowUps = followUpEmployeeIds == null
                ? followUpRepository.findUpcoming(today, top5ScheduledAsc)
                : followUpRepository.findUpcomingForEmployees(today, followUpEmployeeIds, top5ScheduledAsc);

        return new DashboardStatsResponse(
                leadCount, customerCount, opportunityCount, followUpCount,
                pipelineCounts.pipeline(), recentLeads, upcomingFollowUps,
                pipelineCounts.inProgressBreakdown()
        );
    }

    private DashboardStatsResponse.PipelineCounts buildPipeline(List<Long> employeeIds) {

        List<Object[]> rows = employeeIds == null
                ? opportunityRepository.countAllGroupedBySalesStage()
                : (employeeIds.isEmpty()
                        ? List.of()
                        : opportunityRepository.countGroupedBySalesStageForEmployees(employeeIds));

        return tally(rows);
    }

    // Folds [stage name, stage type, sub-status, count] rows into the
    // dashboard buckets. Every opportunity lands in exactly one pipeline
    // bucket, decided by the stage's stable TYPE - not its display name - so
    // an Admin renaming WON / LOST / POSTPONED / IN PROGRESS cannot move
    // opportunities between buckets:
    //  - IN_PROGRESS stages and the historical QUOTATION SENT / NEGOTIATION
    //    stages (now sub-statuses of IN PROGRESS) all count as IN_PROGRESS;
    //  - the in-progress breakdown then splits those same opportunities by
    //    sub-status, so it sums to IN_PROGRESS rather than adding to it.
    // Untyped (custom / historical) stages fall into OTHER.
    static DashboardStatsResponse.PipelineCounts tally(List<Object[]> rows) {

        Map<String, Long> pipeline = new LinkedHashMap<>();
        for (String bucket : PIPELINE_BUCKETS) {
            pipeline.put(bucket, 0L);
        }

        Map<String, Long> breakdown = new LinkedHashMap<>();
        breakdown.put("QUOTATION_SENT", 0L);
        breakdown.put("NEGOTIATION", 0L);
        breakdown.put("NO_SUB_STATUS", 0L);

        for (Object[] row : rows) {

            SalesStageType type = (SalesStageType) row[1];
            Object sub = row[2];
            long count = ((Number) row[3]).longValue();

            String bucket;

            if (type == null) {
                bucket = "OTHER";
            } else {
                bucket = switch (type) {
                    case NEW -> "NEW";
                    case POSTPONED -> "POSTPONED";
                    case WON -> "WON";
                    case LOST -> "LOST";
                    case IN_PROGRESS, LEGACY_QUOTATION_SENT, LEGACY_NEGOTIATION -> "IN_PROGRESS";
                    case LEGACY_DROPPED, LEGACY_UNRESPONSIVE, LEGACY_INVALID -> "OTHER";
                };
            }

            pipeline.merge(bucket, count, Long::sum);

            if (bucket.equals("IN_PROGRESS")) {

                String subKey = type == SalesStageType.LEGACY_QUOTATION_SENT ? "QUOTATION_SENT"
                        : type == SalesStageType.LEGACY_NEGOTIATION ? "NEGOTIATION"
                        : sub != null ? sub.toString()
                        : "NO_SUB_STATUS";

                breakdown.merge(subKey, count, Long::sum);
            }
        }

        return new DashboardStatsResponse.PipelineCounts(pipeline, breakdown);
    }
}
