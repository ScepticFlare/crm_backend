package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.search.FollowUpSearchCriteria;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.service.FollowUpService;
import com.compact.crm.util.TableExport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) String excludeIds,
            @RequestParam(required = false) String format
    ) {

        // `excludeIds` (no `ids`): every matching row except these - the
        // "select all N matching records" selection minus unticked rows.
        List<FollowUp> followUps = followUpService.findForExport(
                criteria, parseIds(ids), parseIds(excludeIds), sortBy, sortDir);

        List<TableExport.Column<FollowUp>> columns = List.of(
                TableExport.Column.text("Lead", f -> f.getLead() != null ? f.getLead().getCompanyName() : null),
                TableExport.Column.text("Opportunity", f -> f.getOpportunity() != null ? f.getOpportunity().getTitle() : null),
                TableExport.Column.text("Employee", f -> f.getEmployee() != null ? f.getEmployee().getName() : null),
                TableExport.Column.text("Activity Type", f -> f.getActivityType() != null ? f.getActivityType().getName() : null),
                TableExport.Column.text("Status", f -> f.getStatus() != null ? f.getStatus().name() : null),
                TableExport.Column.dateTime("Scheduled Date", FollowUp::getScheduledDate),
                TableExport.Column.text("Remarks", FollowUp::getRemarks)
        );

        return TableExport.response("followups", format, "Follow-Ups", columns, followUps);
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
