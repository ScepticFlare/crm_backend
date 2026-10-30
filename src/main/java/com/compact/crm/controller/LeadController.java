package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.search.LeadSearchCriteria;
import com.compact.crm.entity.Lead;
import com.compact.crm.enums.LeadCategory;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.service.LeadService;
import com.compact.crm.util.TableExport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return leadService.searchActiveLeads(criteria, page, size, sortBy, sortDir);
    }

    // History / reporting view of every lead category, RBAC-scoped like the
    // list. `category`: ALL (default), ACTIVE, NEW, VALID_UNCONVERTED,
    // INVALID or CONVERTED. Invalid and converted leads are NOT in the
    // operational list above; this is where they stay reachable.
    @GetMapping("/report")
    public Page<Lead> getLeadsByCategory(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        return leadService.searchLeadsInCategories(
                criteria, LeadCategory.parseSelection(category, null), page, size, sortBy, sortDir);
    }

    // Count per category (+ TOTAL) for the same filters, ignoring `category`.
    @GetMapping("/report/counts")
    public Map<String, Long> getLeadCategoryCounts(@ModelAttribute LeadSearchCriteria criteria) {
        return leadService.countByCategory(criteria);
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
        criteria.setStatus(List.of(LeadStatus.DROPPED));
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
        criteria.setStatus(List.of(LeadStatus.LOST));
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
        criteria.setStatus(List.of(LeadStatus.UNRESPONSIVE));
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
        criteria.setStatus(List.of(LeadStatus.INVALID));
        return leadService.searchLeads(criteria, page, size, sortBy, sortDir);
    }

    // Inactive Leads page: leads whose STORED status is the historical INACTIVE
    // plus leads that are merely stale (no edits or follow-ups for 6+ months,
    // no opportunity), computed read-only - nothing is ever changed. Separate
    // from /invalid (a manual business judgment). Thin wrapper over searchLeads.
    // Same thin-wrapper pattern as every other status bucket above.
    @GetMapping("/inactive")
    public Page<Lead> getInactiveLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        criteria.setInactiveView(true);
        return leadService.searchLeads(criteria, page, size, sortBy, sortDir);
    }

    // Possible-duplicate check used while entering a lead (warning only).
    @GetMapping("/duplicates")
    public com.compact.crm.dto.response.DuplicateLeadsResponse findPossibleDuplicates(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) Long excludeId
    ) {
        return leadService.findPossibleDuplicates(phone, email, company, excludeId);
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

    // Export of the same RBAC-scoped, filtered, sorted result set the list
    // endpoint would return (XLSX by default, `format=csv` for CSV).
    // `category` picks what is exported: ACTIVE (default - the operational
    // Leads page), ALL, or one of NEW / VALID_UNCONVERTED / INVALID /
    // CONVERTED. `ids` (comma-separated), when present, narrows to exactly
    // those rows (bulk "export selected") - still AND-ed with the RBAC scope
    // and the category, so it can never surface a record outside the
    // caller's visibility even if an out-of-scope id is included.
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportLeads(
            @ModelAttribute LeadSearchCriteria criteria,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) String excludeIds,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String format
    ) {

        List<Long> idList = parseIds(ids);
        Set<LeadCategory> categories = LeadCategory.parseSelection(category, LeadCategory.ACTIVE);

        // `excludeIds` (no `ids`): every matching lead except these - the
        // "select all N matching records" selection minus unticked rows.
        List<Lead> leads = leadService.findForExport(
                criteria, idList, parseIds(excludeIds), sortBy, sortDir, categories);

        List<TableExport.Column<Lead>> columns = List.of(
                TableExport.Column.text("Company", Lead::getCompanyName),
                TableExport.Column.text("Contact Person", Lead::getContactPerson),
                TableExport.Column.text("Phone", Lead::getPhone),
                TableExport.Column.text("Alternate Phone", Lead::getAlternatePhone),
                TableExport.Column.text("Email", Lead::getEmail),
                TableExport.Column.text("City", Lead::getCity),
                TableExport.Column.text("State", Lead::getState),
                TableExport.Column.text("Lead Category", l -> l.getCategory() != null ? categoryLabel(l.getCategory()) : null),
                TableExport.Column.text("Lead Status", l -> l.getLeadStatus() != null ? l.getLeadStatus().name() : null),
                TableExport.Column.text("Invalid Reason", Lead::getInvalidReason),
                TableExport.Column.text("Lead Source", l -> l.getLeadSource() != null ? l.getLeadSource().getName() : null),
                TableExport.Column.text("Campaign Code", Lead::getCampaignCode),
                TableExport.Column.text("Industry", l -> l.getIndustry() != null ? l.getIndustry().getName() : null),
                TableExport.Column.text("Assigned Employee", l -> l.getAssignedEmployee() != null ? l.getAssignedEmployee().getName() : null),
                TableExport.Column.dateTime("Created Date", Lead::getCreatedAt)
        );

        return TableExport.response("leads", format, "Leads", columns, leads);
    }

    private static String categoryLabel(LeadCategory category) {
        return switch (category) {
            case NEW -> "New";
            case VALID_UNCONVERTED -> "Valid (not converted)";
            case INVALID -> "Invalid";
            case CONVERTED -> "Converted to opportunity";
        };
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
