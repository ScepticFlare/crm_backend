package com.compact.crm.service;

import com.compact.crm.dto.response.DashboardStatsResponse;
import com.compact.crm.dto.response.RecentLeadResponse;
import com.compact.crm.dto.response.UpcomingFollowUpResponse;
import com.compact.crm.entity.Employee;
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

    private static final List<String> PIPELINE_STAGES =
            List.of("NEW", "QUOTATION_SENT", "NEGOTIATION", "POSTPONED", "WON", "LOST");

    public DashboardStatsResponse getStats() {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        List<Long> leadEmployeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, LEAD_VIEW);
        List<Long> customerEmployeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, CUSTOMER_VIEW);
        List<Long> opportunityEmployeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, OPPORTUNITY_VIEW);
        List<Long> followUpEmployeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, FOLLOWUP_VIEW);

        long leadCount = leadRepository.count(LeadSpecifications.ownerIn(leadEmployeeIds));
        long customerCount = customerRepository.count(CustomerSpecifications.ownerIn(customerEmployeeIds));
        long opportunityCount = opportunityRepository.count(OpportunitySpecifications.ownerIn(opportunityEmployeeIds));
        long followUpCount = followUpRepository.count(com.compact.crm.specification.FollowUpSpecifications.ownerIn(followUpEmployeeIds));

        Map<String, Long> pipeline = buildPipeline(opportunityEmployeeIds);

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
                pipeline, recentLeads, upcomingFollowUps
        );
    }

    private Map<String, Long> buildPipeline(List<Long> employeeIds) {

        List<Object[]> rows = employeeIds == null
                ? opportunityRepository.countAllGroupedBySalesStage()
                : (employeeIds.isEmpty()
                        ? List.of()
                        : opportunityRepository.countGroupedBySalesStageForEmployees(employeeIds));

        Map<String, Long> counts = new LinkedHashMap<>();

        for (Object[] row : rows) {
            counts.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> pipeline = new LinkedHashMap<>();
        long other = 0;

        for (Map.Entry<String, Long> entry : counts.entrySet()) {

            if (PIPELINE_STAGES.contains(entry.getKey())) {
                pipeline.put(entry.getKey(), entry.getValue());
            } else {
                other += entry.getValue();
            }
        }

        for (String stage : PIPELINE_STAGES) {
            pipeline.putIfAbsent(stage, 0L);
        }

        pipeline.put("OTHER", other);

        return pipeline;
    }
}
