package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.request.ReturnToInProgressRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.response.WorkflowAlertsResponse;
import com.compact.crm.dto.search.OpportunitySearchCriteria;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.service.OpportunityService;
import com.compact.crm.util.TableExport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        criteria.setStageType(SalesStageType.POSTPONED);
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
        criteria.setStageType(SalesStageType.LEGACY_DROPPED);
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
        criteria.setStageType(SalesStageType.LOST);
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
        criteria.setStageType(SalesStageType.LEGACY_UNRESPONSIVE);
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
        criteria.setStageType(SalesStageType.LEGACY_INVALID);
        return opportunityService.searchOpportunities(criteria, page, size, sortBy, sortDir);
    }

    // Non-blocking Dashboard reminders (expired closing dates, no upcoming
    // follow-up, postponed review dates due). Read-only.
    @GetMapping("/workflow-alerts")
    public WorkflowAlertsResponse getWorkflowAlerts() {
        return opportunityService.getWorkflowAlerts();
    }

    // "OK" on a postponed review-date reminder - does NOT change the stage.
    @PostMapping("/{id}/postponed-reminder/acknowledge")
    public Opportunity acknowledgePostponedReminder(@PathVariable Long id) {
        return opportunityService.acknowledgePostponedReminder(id);
    }

    // Explicit, separate action: POSTPONED -> IN PROGRESS.
    @PostMapping("/{id}/return-to-in-progress")
    public Opportunity returnToInProgress(
            @PathVariable Long id,
            @RequestBody(required = false) ReturnToInProgressRequest request) {

        return opportunityService.returnToInProgress(id, request);
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

    // XLSX by default (`format=csv` for CSV). Same RBAC scope + filters as the
    // list; the caller needs OPPORTUNITY_EXPORT and never gets more than their
    // OPPORTUNITY_VIEW scope (see OpportunityService.findForExport).
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) String format
    ) {

        List<Long> idList = parseIds(ids);
        List<Opportunity> opportunities = opportunityService.findForExport(criteria, idList, sortBy, sortDir);

        List<TableExport.Column<Opportunity>> columns = List.of(
                TableExport.Column.text("Lead Company", o -> o.getLead() != null ? o.getLead().getCompanyName() : null),
                TableExport.Column.text("Contact Person", o -> o.getLead() != null ? o.getLead().getContactPerson() : null),
                TableExport.Column.text("Phone", o -> o.getLead() != null ? o.getLead().getPhone() : null),
                TableExport.Column.text("Email", o -> o.getLead() != null ? o.getLead().getEmail() : null),
                TableExport.Column.text("Opportunity Title", Opportunity::getTitle),
                TableExport.Column.decimal("Value", Opportunity::getProductValue),
                TableExport.Column.date("Expected Closing Date", Opportunity::getExpectedClosingDate),
                TableExport.Column.text("Stage", o -> o.getSalesStage() != null ? o.getSalesStage().getName() : null),
                TableExport.Column.text("Sub-Status", o -> o.getEffectiveSubStatus() != null ? o.getEffectiveSubStatus().name() : null),
                TableExport.Column.text("Assigned Employee", o -> o.getLead() != null && o.getLead().getAssignedEmployee() != null
                        ? o.getLead().getAssignedEmployee().getName() : null),
                TableExport.Column.dateTime("Created Date", Opportunity::getCreatedAt)
        );

        return TableExport.response("opportunities", format, "Opportunities", columns, opportunities);
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
