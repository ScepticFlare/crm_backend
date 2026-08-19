package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.search.OpportunitySearchCriteria;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.service.OpportunityService;
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
@RequestMapping("/api/opportunities")
@RequiredArgsConstructor
public class OpportunityController {

    private final OpportunityService opportunityService;

    @PostMapping("/convert/{leadId}")
    public Opportunity convertLeadToOpportunity(
            @PathVariable Long leadId,
            @RequestBody OpportunityRequest request) {

        return opportunityService.convertLeadToOpportunity(leadId, request);
    }

    @GetMapping
    public Page<Opportunity> getAllOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        return opportunityService.searchOpportunities(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/in-progress")
    public Page<Opportunity> getInProgressOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        return opportunityService.searchInProgressOpportunities(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/postponed")
    public Page<Opportunity> getPostponedOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStageName("POSTPONED");
        return opportunityService.searchOpportunities(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/dropped")
    public Page<Opportunity> getDroppedOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStageName("DROPPED");
        return opportunityService.searchOpportunities(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/lost")
    public Page<Opportunity> getLostOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStageName("LOST");
        return opportunityService.searchOpportunities(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/unresponsive")
    public Page<Opportunity> getUnresponsiveOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStageName("UNRESPONSIVE");
        return opportunityService.searchOpportunities(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/invalid")
    public Page<Opportunity> getInvalidOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStageName("INVALID");
        return opportunityService.searchOpportunities(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/{id}")
    public Opportunity getOpportunityById(@PathVariable Long id) {
        return opportunityService.getOpportunityById(id);
    }

    @PutMapping("/{id}")
    public Opportunity updateOpportunity(
            @PathVariable Long id,
            @RequestBody OpportunityRequest request) {

        return opportunityService.updateOpportunity(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteOpportunity(@PathVariable Long id) {
        opportunityService.deleteOpportunity(id);
    }

    // Preview for the delete confirmation dialog - what deleting this
    // Opportunity will also remove (a Won Customer conversion, and its
    // FollowUps), before the Admin commits to it. Same OPPORTUNITY_DELETE
    // authorization as the delete itself.
    @GetMapping("/{id}/delete-impact")
    public DeleteImpactResponse getOpportunityDeleteImpact(@PathVariable Long id) {
        return opportunityService.getDeleteImpact(id);
    }

    @PostMapping("/bulk-delete")
    public BulkOperationResult bulkDeleteOpportunities(@Valid @RequestBody BulkIdsRequest request) {
        return opportunityService.bulkDeleteOpportunities(request.getIds());
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String ids
    ) {

        List<Long> idList = parseIds(ids);
        List<Opportunity> opportunities = opportunityService.findForExport(criteria, idList, sortBy, sortDir);

        List<String> headers = List.of(
                "Lead Company", "Opportunity Title", "Value", "Expected Closing Date",
                "Stage", "Assigned Employee", "Created Date"
        );

        List<List<String>> rows = new ArrayList<>();

        for (Opportunity o : opportunities) {

            rows.add(List.of(
                    o.getLead() != null && o.getLead().getCompanyName() != null ? o.getLead().getCompanyName() : "",
                    o.getTitle() != null ? o.getTitle() : "",
                    o.getProductValue() != null ? o.getProductValue().toString() : "",
                    o.getExpectedClosingDate() != null ? o.getExpectedClosingDate().toString() : "",
                    o.getSalesStage() != null ? o.getSalesStage().getName() : "",
                    o.getLead() != null && o.getLead().getAssignedEmployee() != null
                            ? o.getLead().getAssignedEmployee().getName() : "",
                    o.getCreatedAt() != null ? o.getCreatedAt().toString() : ""
            ));
        }

        String csv = CsvWriter.write(headers, rows);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.parseMediaType("text/csv"));
        responseHeaders.setContentDisposition(
                ContentDisposition.attachment().filename("opportunities.csv", StandardCharsets.UTF_8).build());

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
