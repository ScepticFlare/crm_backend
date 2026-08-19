package com.compact.crm.service;

import com.compact.crm.dto.response.ActivitySummaryResponse;
import com.compact.crm.dto.search.ActivityLogSearchCriteria;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.specification.ActivityLogSpecifications;
import com.compact.crm.specification.SpecificationUtil;
import com.compact.crm.util.SortWhitelist;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.compact.crm.security.AccessControlService.ACTIVITY_VIEW;

// The write side of the activity/audit log. log(...) is called from other
// services (Lead/Opportunity/Customer/FollowUp/Employee/Auth) right after
// a business action succeeds - never from a controller taking activity
// data straight from a request body, and the actor is always whichever
// Employee the caller already resolved via CurrentUserService, never an
// id read off the request. There is deliberately no endpoint that lets a
// client create/update/delete an ActivityLog row directly.
//
// Logging failures must never break the CRM operation they're attached
// to: the save is wrapped in try/catch here and only logged via SLF4J on
// failure, never rethrown. See ActivityLogServiceTest for the test that
// pins this behavior.
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLogService {

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "createdDate", "createdAt",
            "employee", "employeeName",
            "module", "module",
            "action", "action"
    );

    private final ActivityLogRepository activityLogRepository;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;

    public void log(
            Employee actor,
            ActivityModule module,
            ActivityAction action,
            Long entityId,
            String entityName,
            String description) {

        try {

            ActivityLog entry = ActivityLog.builder()
                    .employee(actor)
                    .employeeName(actor != null ? actor.getName() : "Unknown")
                    .module(module)
                    .action(action)
                    .entityId(entityId)
                    .entityName(entityName)
                    .description(description)
                    .createdAt(LocalDateTime.now())
                    .build();

            activityLogRepository.save(entry);

        } catch (Exception ex) {

            log.error(
                    "Failed to record activity log entry (module={}, action={}, entityId={}): {}",
                    module, action, entityId, ex.getMessage(), ex
            );

        }
    }

    // RBAC-scoped, filtered, paginated activity list - the read side backing
    // GET /api/activity. Same ALL/TEAM/OWN model as every other module via
    // AccessControlService.resolveVisibleEmployeeIds, reused verbatim.
    public Page<ActivityLog> search(
            ActivityLogSearchCriteria criteria, int page, int size, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> scopedEmployeeIds = resolveScopedEmployeeIds(currentEmployee, criteria.getEmployeeId());

        LocalDateTime createdFrom = criteria.getCreatedFrom() != null
                ? criteria.getCreatedFrom().atStartOfDay() : null;

        LocalDateTime createdTo = criteria.getCreatedTo() != null
                ? criteria.getCreatedTo().plusDays(1).atStartOfDay() : null;

        Specification<ActivityLog> spec = SpecificationUtil.and(Arrays.asList(
                scopedEmployeeIds != null ? ActivityLogSpecifications.actorIn(scopedEmployeeIds) : null,
                ActivityLogSpecifications.hasModule(criteria.getModule()),
                ActivityLogSpecifications.hasAction(criteria.getAction()),
                ActivityLogSpecifications.createdBetween(createdFrom, createdTo)
        ));

        // Newest first by default - an activity feed reads naturally
        // chronologically-descending, unlike most other list endpoints
        // here which default ascending.
        Sort sort = SortWhitelist.resolve(sortBy, sortDir != null ? sortDir : "desc", SORT_FIELDS, "createdDate");

        return activityLogRepository.findAll(spec, PageRequest.of(page, size, sort));
    }

    // Stat-card row backing GET /api/activity/summary. Scoped identically to
    // search() (employeeId + date-range only - module/action aren't applied
    // here since each stat below already names its own module+action pair).
    // Targeted count()/single-row queries throughout, never a full row
    // fetch, so this stays cheap regardless of table size.
    public ActivitySummaryResponse getSummary(ActivityLogSearchCriteria criteria) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> scopedEmployeeIds = resolveScopedEmployeeIds(currentEmployee, criteria.getEmployeeId());

        LocalDateTime createdFrom = criteria.getCreatedFrom() != null
                ? criteria.getCreatedFrom().atStartOfDay() : null;

        LocalDateTime createdTo = criteria.getCreatedTo() != null
                ? criteria.getCreatedTo().plusDays(1).atStartOfDay() : null;

        Specification<ActivityLog> base = SpecificationUtil.and(Arrays.asList(
                scopedEmployeeIds != null ? ActivityLogSpecifications.actorIn(scopedEmployeeIds) : null,
                ActivityLogSpecifications.createdBetween(createdFrom, createdTo)
        ));

        long totalActions = activityLogRepository.count(base);

        long leadsCreated = activityLogRepository.count(withModuleAction(base, ActivityModule.LEAD, ActivityAction.CREATE));
        long leadsUpdated = activityLogRepository.count(withModuleAction(base, ActivityModule.LEAD, ActivityAction.UPDATE));
        long followUpsCompleted = activityLogRepository.count(withModuleAction(base, ActivityModule.FOLLOWUP, ActivityAction.COMPLETE));

        long conversions = activityLogRepository.count(SpecificationUtil.and(Arrays.asList(
                base, ActivityLogSpecifications.hasAction(ActivityAction.CONVERT)
        )));

        LocalDateTime lastActiveAt = mostRecentTimestamp(base);

        LocalDateTime lastLoginAt = mostRecentTimestamp(SpecificationUtil.and(Arrays.asList(
                base, ActivityLogSpecifications.hasAction(ActivityAction.LOGIN)
        )));

        return new ActivitySummaryResponse(
                totalActions, leadsCreated, leadsUpdated, followUpsCompleted, conversions,
                lastActiveAt, lastLoginAt
        );
    }

    private Specification<ActivityLog> withModuleAction(
            Specification<ActivityLog> base, ActivityModule module, ActivityAction action) {

        return SpecificationUtil.and(Arrays.asList(
                base,
                ActivityLogSpecifications.hasModule(module),
                ActivityLogSpecifications.hasAction(action)
        ));
    }

    private LocalDateTime mostRecentTimestamp(Specification<ActivityLog> spec) {

        return activityLogRepository
                .findAll(spec, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .findFirst()
                .map(ActivityLog::getCreatedAt)
                .orElse(null);
    }

    // Resolves the employee-id filter this request should apply: the
    // caller's RBAC-scoped visible set (null = ALL, from
    // AccessControlService.resolveVisibleEmployeeIds), intersected against
    // an explicit employeeId filter if one was requested. A requested
    // employeeId outside the caller's visible set is rejected outright
    // (AccessDeniedException) rather than silently returning an empty
    // result - this is what stops a Manager from viewing another team's
    // (or an Employee from viewing another employee's) activity by simply
    // passing a different id in the filter.
    private List<Long> resolveScopedEmployeeIds(Employee currentEmployee, Long requestedEmployeeId) {

        List<Long> visibleIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, ACTIVITY_VIEW);

        if (requestedEmployeeId == null) {
            return visibleIds;
        }

        if (visibleIds != null && !visibleIds.contains(requestedEmployeeId)) {
            throw new AccessDeniedException("You are not authorized to view this employee's activity.");
        }

        return List.of(requestedEmployeeId);
    }
}
