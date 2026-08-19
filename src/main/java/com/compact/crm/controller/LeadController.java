package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.search.LeadSearchCriteria;
import com.compact.crm.entity.Lead;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.service.LeadService;
import com.compact.crm.util.CsvWriter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;

    @PostMapping
    public Lead createLead(@Valid @RequestBody LeadRequest request) {
        return leadService.createLead(request);
    }

    // Combinable search + filters + sort + pagination, all in one endpoint.
    // See dto.search.LeadSearchCriteria for the full filter set.
    @GetMapping
    public Page<Lead> getAllLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        return leadService.searchLeads(criteria, page, size, sortBy, sortDir);
    }

    // Status-bucket routes are thin wrappers over the same searchLeads
    // query - the status filter is forced, everything else (search, other
    // filters, sort, pagination) still applies exactly as on the main list.
    @GetMapping("/dropped")
    public Page<Lead> getDroppedLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStatus(LeadStatus.DROPPED);
        return leadService.searchLeads(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/lost")
    public Page<Lead> getLostLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStatus(LeadStatus.LOST);
        return leadService.searchLeads(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/unresponsive")
    public Page<Lead> getUnresponsiveLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStatus(LeadStatus.UNRESPONSIVE);
        return leadService.searchLeads(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/invalid")
    public Page<Lead> getInvalidLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStatus(LeadStatus.INVALID);
        return leadService.searchLeads(criteria, page, size, sortBy, sortDir);
    }

    // Leads scheduler.StaleLeadScheduler automatically moved to INACTIVE
    // after 6+ months of no meaningful activity - a separate bucket from
    // /invalid (genuinely bad/unusable Leads, a manual business judgment).
    // Same thin-wrapper pattern as every other status bucket above.
    @GetMapping("/inactive")
    public Page<Lead> getInactiveLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setStatus(LeadStatus.INACTIVE);
        return leadService.searchLeads(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/{id}")
    public Lead getLeadById(@PathVariable Long id) {
        return leadService.getLeadById(id);
    }

    @PutMapping("/{id}")
    public Lead updateLead(@PathVariable Long id, @Valid @RequestBody LeadRequest request) {
        return leadService.updateLead(id, request);
    }

    @DeleteMapping("/{id}")
    public String deleteLead(@PathVariable Long id) {
        leadService.deleteLead(id);
        return "Lead deleted successfully";
    }

    // Preview for the delete confirmation dialog - what deleting this Lead
    // will also remove (its Opportunity, a Won Customer conversion, and
    // every FollowUp in that chain), before the Admin commits to it. Same
    // LEAD_DELETE authorization as the delete itself.
    @GetMapping("/{id}/delete-impact")
    public DeleteImpactResponse getLeadDeleteImpact(@PathVariable Long id) {
        return leadService.getDeleteImpact(id);
    }

    // Deletes only the ids the caller is authorized (LEAD_MANAGE) to
    // delete - see LeadService.bulkDeleteLeads for the exact semantics.
    @PostMapping("/bulk-delete")
    public BulkOperationResult bulkDeleteLeads(@Valid @RequestBody BulkIdsRequest request) {
        return leadService.bulkDeleteLeads(request.getIds());
    }

    // CSV export of the same RBAC-scoped, filtered, sorted result set the
    // list endpoint would return. `ids` (comma-separated), when present,
    // narrows to exactly those rows (bulk "export selected") - still
    // AND-ed with the RBAC scope, so it can never surface a record outside
    // the caller's visibility even if an out-of-scope id is included.
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String ids
    ) {

        List<Long> idList = parseIds(ids);

        List<Lead> leads = leadService.findForExport(criteria, idList, sortBy, sortDir);

        List<String> headers = List.of(
                "Company", "Contact Person", "Phone", "Email", "City", "State",
                "Lead Status", "Lead Source", "Industry", "Assigned Employee", "Created Date"
        );

        List<List<String>> rows = new ArrayList<>();

        for (Lead lead : leads) {

            rows.add(List.of(
                    nullToEmpty(lead.getCompanyName()),
                    nullToEmpty(lead.getContactPerson()),
                    nullToEmpty(lead.getPhone()),
                    nullToEmpty(lead.getEmail()),
                    nullToEmpty(lead.getCity()),
                    nullToEmpty(lead.getState()),
                    lead.getLeadStatus() != null ? lead.getLeadStatus().name() : "",
                    lead.getLeadSource() != null ? lead.getLeadSource().getName() : "",
                    lead.getIndustry() != null ? lead.getIndustry().getName() : "",
                    lead.getAssignedEmployee() != null ? lead.getAssignedEmployee().getName() : "",
                    lead.getCreatedAt() != null ? lead.getCreatedAt().toString() : ""
            ));
        }

        String csv = CsvWriter.write(headers, rows);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.parseMediaType("text/csv"));
        responseHeaders.setContentDisposition(
                ContentDisposition.attachment().filename("leads.csv", StandardCharsets.UTF_8).build());

        return new ResponseEntity<>(body, responseHeaders, org.springframework.http.HttpStatus.OK);
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

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
