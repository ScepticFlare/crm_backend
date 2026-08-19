package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.search.FollowUpSearchCriteria;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.service.FollowUpService;
import com.compact.crm.util.CsvWriter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/followups")
@RequiredArgsConstructor
public class FollowUpController {

    private final FollowUpService followUpService;

    @PostMapping
    public FollowUp createFollowUp(@RequestBody FollowUpRequest request) {
        return followUpService.createFollowUp(request);
    }

    @GetMapping
    public Page<FollowUp> getAllFollowUps(
            @ModelAttribute FollowUpSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        return followUpService.searchFollowUps(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/lead/{leadId}")
    public List<FollowUp> getFollowUpsByLead(
            @PathVariable Long leadId) {

        return followUpService.getFollowUpsByLead(leadId);

    }

    @GetMapping("/opportunity/{opportunityId}")
    public List<FollowUp> getFollowUpsByOpportunity(
            @PathVariable Long opportunityId) {

        return followUpService.getFollowUpsByOpportunity(opportunityId);

    }

    @GetMapping("/{id}")
    public FollowUp getFollowUpById(@PathVariable Long id) {
        return followUpService.getFollowUpById(id);
    }

    @PutMapping("/{id}")
    public FollowUp updateFollowUp(@PathVariable Long id,
                                   @RequestBody FollowUpRequest request) {
        return followUpService.updateFollowUp(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteFollowUp(@PathVariable Long id) {
        followUpService.deleteFollowUp(id);
    }

    @PostMapping("/bulk-delete")
    public BulkOperationResult bulkDeleteFollowUps(@Valid @RequestBody BulkIdsRequest request) {
        return followUpService.bulkDeleteFollowUps(request.getIds());
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportFollowUps(
            @ModelAttribute FollowUpSearchCriteria criteria,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String ids
    ) {

        List<Long> idList = parseIds(ids);
        List<FollowUp> followUps = followUpService.findForExport(criteria, idList, sortBy, sortDir);

        List<String> headers = List.of(
                "Lead", "Opportunity", "Employee", "Activity Type", "Status", "Scheduled Date", "Remarks"
        );

        List<List<String>> rows = new ArrayList<>();

        for (FollowUp f : followUps) {

            rows.add(List.of(
                    f.getLead() != null && f.getLead().getCompanyName() != null ? f.getLead().getCompanyName() : "",
                    f.getOpportunity() != null && f.getOpportunity().getTitle() != null ? f.getOpportunity().getTitle() : "",
                    f.getEmployee() != null ? f.getEmployee().getName() : "",
                    f.getActivityType() != null ? f.getActivityType().getName() : "",
                    f.getStatus() != null ? f.getStatus().name() : "",
                    f.getScheduledDate() != null ? f.getScheduledDate().toString() : "",
                    f.getRemarks() != null ? f.getRemarks() : ""
            ));
        }

        String csv = CsvWriter.write(headers, rows);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.parseMediaType("text/csv"));
        responseHeaders.setContentDisposition(
                ContentDisposition.attachment().filename("followups.csv", StandardCharsets.UTF_8).build());

        return new ResponseEntity<>(body, responseHeaders, HttpStatus.OK);
    }

    private List<Long> parseIds(String ids) {

        if (ids == null || ids.isBlank()) {
            return null;
        }

        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }
}
