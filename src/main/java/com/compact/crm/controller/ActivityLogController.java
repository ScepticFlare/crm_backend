package com.compact.crm.controller;

import com.compact.crm.dto.response.ActivitySummaryResponse;
import com.compact.crm.dto.search.ActivityLogSearchCriteria;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

// Read-only by design: there is deliberately no POST/PUT/DELETE here. Every
// activity entry is written internally by ActivityLogService.log(...) from
// inside other services, right after a real business action succeeds -
// never from a client request. RBAC (ADMIN=all, MANAGER=own+team,
// EMPLOYEE=own) is enforced in ActivityLogService via AccessControlService,
// not here - this controller is a thin pass-through, same convention as
// LeadController/CustomerController/etc.
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityLogController {

    private final ActivityLogService activityLogService;

    @GetMapping
    public Page<ActivityLog> getActivity(
            @ModelAttribute ActivityLogSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        return activityLogService.search(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/summary")
    public ActivitySummaryResponse getSummary(@ModelAttribute ActivityLogSearchCriteria criteria) {
        return activityLogService.getSummary(criteria);
    }
}
