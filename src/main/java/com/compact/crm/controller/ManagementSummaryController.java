package com.compact.crm.controller;

import com.compact.crm.dto.response.ManagementSummaryResponse;
import com.compact.crm.service.ManagementSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// Management metrics (funnel, pipeline, overdue, closing windows, wins/losses,
// loss reasons, closure-date changes, uncontacted leads). Read-only and
// scoped by the caller's existing view permissions inside the service - the
// same ALL / TEAM / OWN model as every list endpoint.
@RestController
@RequestMapping("/api/management")
@RequiredArgsConstructor
public class ManagementSummaryController {

    private final ManagementSummaryService service;

    @GetMapping("/summary")
    public ManagementSummaryResponse getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return service.getSummary(from, to);
    }
}
