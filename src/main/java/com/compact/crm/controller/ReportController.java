package com.compact.crm.controller;

import com.compact.crm.dto.request.FullReportRequest;
import com.compact.crm.dto.response.FullReportResponse;
import com.compact.crm.dto.response.LeadReportResponse;
import com.compact.crm.service.ExcelExportService;
import com.compact.crm.service.FullReportExcelExportService;
import com.compact.crm.service.FullReportService;
import com.compact.crm.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final ReportService reportService;
    private final ExcelExportService excelExportService;
    private final FullReportService fullReportService;
    private final FullReportExcelExportService fullReportExcelExportService;

    @GetMapping("/leads")
    public List<LeadReportResponse> getLeadReport(

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,

            @RequestParam(required = false)
            Long leadSourceId

    ) {

        return reportService.generateLeadReport(
                from,
                to,
                leadSourceId
        );
    }

    @GetMapping("/leads/export")
    public ResponseEntity<byte[]> exportLeadReport(

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,

            @RequestParam(required = false)
            Long leadSourceId

    ) throws IOException {

        List<LeadReportResponse> report =
                reportService.generateLeadReport(
                        from,
                        to,
                        leadSourceId
                );

        byte[] excel = excelExportService.exportLeadReport(report);

        HttpHeaders headers = new HttpHeaders();

        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename("Lead_Report.xlsx")
                        .build()
        );

        headers.setContentType(
                MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
        );

        return ResponseEntity
                .ok()
                .headers(headers)
                .body(excel);
    }

    @PostMapping("/full")
    public FullReportResponse getFullReport(
            @RequestBody FullReportRequest request
    ) {

        return fullReportService.generateFullReport(request);
    }

    @PostMapping("/full/export")
    public ResponseEntity<byte[]> exportFullReport(
            @RequestBody FullReportRequest request
    ) throws IOException {

        FullReportResponse report = fullReportService.generateFullReport(request);

        byte[] excel = fullReportExcelExportService.export(
                report,
                FullReportService.normalizedRecordTypes(request.getRecordTypes()),
                FullReportService.normalizedSections(request.getIncludeSections())
        );

        HttpHeaders headers = new HttpHeaders();

        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename("Full_Report.xlsx")
                        .build()
        );

        headers.setContentType(
                MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
        );

        return ResponseEntity
                .ok()
                .headers(headers)
                .body(excel);
    }
}