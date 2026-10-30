package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.request.ReturnToInProgressRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.response.WorkflowAlertsResponse;
import com.compact.crm.dto.search.OpportunitySearchCriteria;
import com.compact.crm.entity.Lead;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // Every column an Opportunity export can contain, keyed by the same keys
    // as the Opportunities list's column chooser (pages/Opportunities.jsx).
    // Contact details, products and batteries are the Opportunity's OWN
    // current values (editable after conversion), not the original Lead's.
    private static final Map<String, TableExport.Column<Opportunity>> EXPORT_COLUMNS = exportColumns();

    // Used when the request names no (known) columns - an older client.
    private static final List<String> DEFAULT_EXPORT_COLUMNS = List.of(
            "company", "contactPerson", "contactPhone", "contactEmail", "title", "products", "batteries",
            "value", "date", "stage", "subStatus", "assignedEmployee", "createdAt");

    private static Map<String, TableExport.Column<Opportunity>> exportColumns() {

        Map<String, TableExport.Column<Opportunity>> c = new LinkedHashMap<>();

        c.put("company", TableExport.Column.text("Company", o -> lead(o) != null ? lead(o).getCompanyName() : null));
        c.put("title", TableExport.Column.text("Opportunity Title", Opportunity::getTitle));
        c.put("contactPerson", TableExport.Column.text("Contact Person", Opportunity::getContactPerson));
        c.put("contactPhone", TableExport.Column.text("Contact Phone", Opportunity::getContactPhone));
        c.put("contactEmail", TableExport.Column.text("Contact Email", Opportunity::getContactEmail));
        c.put("products", TableExport.Column.text("Products", OpportunityService::describeProducts));
        c.put("batteries", TableExport.Column.text("Batteries", OpportunityService::describeBatteries));
        c.put("value", TableExport.Column.decimal("Value", Opportunity::getProductValue));
        c.put("date", TableExport.Column.date("Expected Closing Date", Opportunity::getExpectedClosingDate));
        c.put("stage", TableExport.Column.text("Stage", o -> o.getSalesStage() != null ? o.getSalesStage().getName() : null));
        c.put("subStatus", TableExport.Column.text("Sub-Status",
                o -> o.getEffectiveSubStatus() != null ? o.getEffectiveSubStatus().name() : null));
        c.put("assignedEmployee", TableExport.Column.text("Assigned Employee",
                o -> lead(o) != null && lead(o).getAssignedEmployee() != null ? lead(o).getAssignedEmployee().getName() : null));
        c.put("industry", TableExport.Column.text("Industry",
                o -> lead(o) != null && lead(o).getIndustry() != null ? lead(o).getIndustry().getName() : null));
        c.put("city", TableExport.Column.text("City", o -> lead(o) != null ? lead(o).getCity() : null));
        c.put("state", TableExport.Column.text("State", o -> lead(o) != null ? lead(o).getState() : null));
        c.put("leadSource", TableExport.Column.text("Lead Source",
                o -> lead(o) != null && lead(o).getLeadSource() != null ? lead(o).getLeadSource().getName() : null));
        c.put("leadValidity", TableExport.Column.text("Validity",
                o -> o.getLeadValidity() != null ? o.getLeadValidity().name() : null));
        c.put("nextFollowUp", TableExport.Column.dateTime("Next Follow-up", Opportunity::getNextFollowUpDate));
        c.put("postponedReviewDate", TableExport.Column.date("Postponed Review Date", Opportunity::getPostponedReviewDate));
        c.put("postponedReason", TableExport.Column.text("Postponed Reason", Opportunity::getPostponedReason));
        c.put("lostReason", TableExport.Column.text("Lost Reason", Opportunity::getLostReason));
        c.put("createdAt", TableExport.Column.dateTime("Created Date", Opportunity::getCreatedAt));
        c.put("updatedAt", TableExport.Column.dateTime("Last Modified", Opportunity::getUpdatedAt));

        return c;
    }

    private static Lead lead(Opportunity o) {
        return o.getLead();
    }

    // XLSX by default (`format=csv` for CSV). Same RBAC scope + filters as the
    // list; the caller needs OPPORTUNITY_EXPORT and never gets more than their
    // OPPORTUNITY_VIEW scope (see OpportunityService.findForExport).
    //  - `columns`: comma-separated column keys (see EXPORT_COLUMNS), in order.
    //  - `ids`: export exactly these rows (still scoped and filtered).
    //  - `excludeIds`: with no `ids`, every matching row except these - the
    //    "select all N matching records" selection minus unticked rows.
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportOpportunities(
            @ModelAttribute OpportunitySearchCriteria criteria,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) String excludeIds,
            @RequestParam(required = false) String columns,
            @RequestParam(required = false) String format
    ) {

        List<Opportunity> opportunities = opportunityService.findForExport(
                criteria, parseIds(ids), parseIds(excludeIds), sortBy, sortDir);

        return TableExport.response("opportunities", format, "Opportunities",
                TableExport.select(EXPORT_COLUMNS, columns, DEFAULT_EXPORT_COLUMNS), opportunities);
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
