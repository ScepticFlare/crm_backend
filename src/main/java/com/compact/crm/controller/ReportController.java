package com.compact.crm.controller;

import com.compact.crm.dto.response.LeadReportResponse;
import com.compact.crm.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/leads")
    public LeadReportResponse getLeadReport(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to
    ) {

        return reportService.getLeadReport(from, to);

    }

}