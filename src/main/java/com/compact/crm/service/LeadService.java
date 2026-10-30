package com.compact.crm.service;

import com.compact.crm.dto.request.LeadBatteryRequest;
import com.compact.crm.dto.request.LeadProductRequest;
import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.response.DuplicateLeadsResponse;
import com.compact.crm.dto.search.LeadSearchCriteria;
import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Product;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.LeadCategory;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.BatteryRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.specification.LeadSpecifications;
import com.compact.crm.specification.SpecificationUtil;
import com.compact.crm.util.SortWhitelist;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import com.compact.crm.util.BusinessClock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.compact.crm.security.AccessControlService.LEAD_DELETE;
import static com.compact.crm.security.AccessControlService.LEAD_EXPORT;
import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;

@Service
@RequiredArgsConstructor
public class LeadService {

    // Frontend logical sort keys -> real JPA property paths. See
    // util.SortWhitelist - an unrecognized sortBy silently falls back to
    // "createdDate" rather than reaching the query.
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "company", "companyName",
            "contactPerson", "contactPerson",
            "status", "leadStatus",
            "createdDate", "createdAt",
            "assignedEmployee", "assignedEmployee.name"
    );

    // Safety cap on export size - a filtered/RBAC-scoped export is still
    // "all matching rows", which could otherwise be unbounded.
    private static final int EXPORT_MAX_ROWS = 20_000;

    // Resolved/terminal Lead outcomes - drives closedAt (set on transition
    // into this set, cleared on transition out of it). Same set
    // OpportunityService.CLOSED_STAGE_NAMES and
    // FullReportService.NON_IN_PROGRESS_STAGES use for the analogous
    // Opportunity Sales Stage concept. INACTIVE is deliberately excluded -
    // it is a historical staleness value, not a decided business outcome.
    private static final Set<LeadStatus> CLOSED_STATUSES = Set.of(
            LeadStatus.WON, LeadStatus.LOST, LeadStatus.DROPPED,
            LeadStatus.UNRESPONSIVE, LeadStatus.INVALID
    );

    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;
    private final LeadRepository leadRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductRepository productRepository;
    private final IndustryRepository industryRepository;
    private final LeadSourceMasterRepository leadSourceMasterRepository;
    private final BatteryRepository batteryRepository;
    private final ActivityLogService activityLogService;
    // Cascade-delete dependencies (see deleteLead/getDeleteImpact): a Lead
    // may have one Opportunity (OpportunityRepository), which may itself
    // have a Won Customer conversion and FollowUps - handled by delegating
    // to OpportunityService.cascadeDeleteOpportunity rather than
    // duplicating that logic here. FollowUpRepository is still needed
    // directly for FollowUps attached to the Lead itself (a FollowUp can
    // belong to a Lead OR an Opportunity, never both).
    private final OpportunityRepository opportunityRepository;
    private final FollowUpRepository followUpRepository;
    private final OpportunityService opportunityService;

    // Months of inactivity after which an open, unconverted lead is shown as
    // stale (read-only flag; see StaleLeadRules). Defaults to 6.
    @org.springframework.beans.factory.annotation.Value("${crm.lead.stale-after-months:6}")
    private int staleAfterMonths;

    public Lead createLead(LeadRequest request) {

        Employee employee = employeeRepository.findById(request.getAssignedEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        // Validated before anything is built: a lead is created as NEW, VALID
        // or INVALID (never one of the older legacy statuses), and an INVALID
        // lead needs a reason.
        String invalidReason = validateStatusAndReason(
                request.getLeadStatus(), null, null, request.getInvalidReason());

        // A new lead must say what the customer wants: at least one Product
        // and at least one Battery. Checked here (not only in the form) so a
        // direct API call cannot skip it. Not a database constraint - older
        // leads without them still exist and stay editable (see updateLead).
        requireLineItems(request, true, true);

        Lead lead = Lead.builder()
                .companyName(request.getCompanyName())
                .contactPerson(request.getContactPerson())
                .designation(request.getDesignation())
                .phone(request.getPhone())
                .alternatePhone(request.getAlternatePhone())
                .email(request.getEmail())
                .secondaryEmail(request.getSecondaryEmail())
                .city(request.getCity())
                .state(request.getState())
                .pincode(request.getPincode())
                .industry(
                        industryRepository.findById(request.getIndustryId())
                                .orElseThrow(() -> new ResourceNotFoundException("Industry not found"))
                )
                .description(request.getDescription())
                .leadStatus(request.getLeadStatus())
                .invalidReason(invalidReason)
                .contactDeadline(request.getContactDeadline() != null
                        ? request.getContactDeadline()
                        : BusinessClock.today().plusDays(LeadWorkflow.DEFAULT_CONTACT_DEADLINE_DAYS))
                .closedAt(request.getLeadStatus() != null && CLOSED_STATUSES.contains(request.getLeadStatus())
                        ? LocalDateTime.now() : null)
                .leadValidity(validityFor(request.getLeadStatus(), null))
                .leadSource(
                        leadSourceMasterRepository.findById(request.getLeadSourceId())
                                .orElseThrow(() -> new ResourceNotFoundException("Lead Source not found"))
                )
                .assignedEmployee(employee)
                .build();
        lead.setFinalRemarks(request.getFinalRemarks());

        applyBatteries(lead, request.getBatteries());
        applyProducts(lead, request.getProducts());

        Lead saved = leadRepository.save(lead);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.LEAD, ActivityAction.CREATE,
                saved.getId(), saved.getCompanyName(),
                "Created lead"
        );

        decorateLeads(List.of(saved));

        return saved;
    }

    private static String trimToNull(String value) {

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    static final String PRODUCT_REQUIRED_MESSAGE = "Please select at least one Product.";
    static final String BATTERY_REQUIRED_MESSAGE = "Please select at least one Battery.";

    // Rows without a selected item are not counted (the form can send an
    // unfilled row); each kept row's id/quantity is validated by @Valid.
    private static void requireLineItems(LeadRequest request, boolean productRequired, boolean batteryRequired) {

        boolean hasProduct = request.getProducts() != null
                && request.getProducts().stream().anyMatch(p -> p != null && p.getProductId() != null);

        boolean hasBattery = request.getBatteries() != null
                && request.getBatteries().stream().anyMatch(b -> b != null && b.getBatteryId() != null);

        if (productRequired && !hasProduct) {
            throw new IllegalArgumentException(PRODUCT_REQUIRED_MESSAGE);
        }

        if (batteryRequired && !hasBattery) {
            throw new IllegalArgumentException(BATTERY_REQUIRED_MESSAGE);
        }
    }

    // Status rules of the simplified Lead workflow, checked before anything
    // is changed. Returns the invalid reason to store (null unless INVALID).
    //  - Only NEW / VALID / INVALID can be chosen. A lead that already carries
    //    an older legacy status may keep it (an unrelated edit must not
    //    force a status change) but nobody can newly set one.
    //  - INVALID needs a reason when the lead becomes INVALID (or already has
    //    one). A historical INVALID lead with no reason stays editable - no
    //    reason is invented for it.
    private String validateStatusAndReason(
            LeadStatus requested, LeadStatus previous, String previousReason, String requestedReason) {

        if (requested == null) {
            throw new IllegalArgumentException("Please select a Lead Status.");
        }

        if (!LeadWorkflow.isCore(requested) && requested != previous) {
            throw new IllegalArgumentException(
                    "Lead Status must be New, Valid or Invalid. (" + requested.name().replace('_', ' ')
                            + " is an older status and can no longer be selected.)");
        }

        if (requested != LeadStatus.INVALID) {
            return null;
        }

        String reason = trimToNull(requestedReason);

        boolean wasInvalid = previous == LeadStatus.INVALID;
        boolean hadReason = trimToNull(previousReason) != null;

        if (reason == null && (!wasInvalid || hadReason)) {
            throw new IllegalArgumentException("Please enter the reason this lead is invalid.");
        }

        return reason;
    }

    // Lead validity follows the status so the two can never contradict each
    // other: INVALID <-> INVALID; NEW and VALID <-> VALID. A lead still
    // carrying a legacy status keeps whatever validity it had.
    private static LeadValidity validityFor(LeadStatus status, LeadValidity current) {

        if (status == LeadStatus.INVALID) {
            return LeadValidity.INVALID;
        }

        if (status == LeadStatus.NEW || status == LeadStatus.VALID) {
            return LeadValidity.VALID;
        }

        return current != null ? current : LeadValidity.VALID;
    }

    // Marks the lead rows with the fields computed for the UI: converted
    // (an Opportunity exists) and contactOverdue (NEW, deadline passed, no
    // completed follow-up). One pair of queries for the whole batch.
    void decorateLeads(Collection<Lead> leads) {

        List<Long> ids = leads.stream().map(Lead::getId).filter(Objects::nonNull).toList();

        if (ids.isEmpty()) {
            return;
        }

        Set<Long> converted = new HashSet<>(opportunityRepository.findLeadIdsWithOpportunity(ids));
        Set<Long> contacted = new HashSet<>(followUpRepository.findLeadIdsWithCompletedFollowUp(ids));

        Set<Long> withNextAction = new HashSet<>();

        for (Object[] row : followUpRepository.findPendingFutureByLeadIds(ids, BusinessClock.now())) {

            if (FollowUpRules.hasDescription((String) row[1])) {
                withNextAction.add((Long) row[0]);
            }
        }

        LocalDateTime staleCutoff = StaleLeadRules.cutoff(staleAfterMonths);
        Set<Long> recentlyActive = new HashSet<>(followUpRepository.findLeadIdsWithFollowUpSince(ids, staleCutoff));

        LocalDate today = BusinessClock.today();

        for (Lead lead : leads) {

            boolean isConverted = converted.contains(lead.getId());

            lead.setConverted(isConverted);

            boolean overdue = lead.getContactDeadline() != null
                    && lead.getContactDeadline().isBefore(today)
                    && LeadWorkflow.effectiveStatus(lead.getLeadStatus(), isConverted) == LeadStatus.NEW
                    && !contacted.contains(lead.getId());

            lead.setContactOverdue(overdue);

            lead.setHasNextAction(withNextAction.contains(lead.getId()));

            lead.setStale(!isConverted
                    && lead.getLeadStatus() != null
                    && StaleLeadRules.OPEN_STATUSES.contains(lead.getLeadStatus())
                    && lead.getUpdatedAt() != null
                    && lead.getUpdatedAt().isBefore(staleCutoff)
                    && !recentlyActive.contains(lead.getId()));
        }
    }

    // Single reusable list query backing both the main /api/leads endpoint
    // and every status-bucket endpoint (which just force criteria.status
    // before calling this) - combinable search + filters + a whitelisted
    // sort + RBAC scope, all folded into one Specification.
    //
    // NOT category-restricted: the status-bucket endpoints (/invalid, /lost,
    // /inactive...) and the report endpoint rely on seeing every category.
    // The operational Leads page must go through searchActiveLeads instead.
    public Page<Lead> searchLeads(LeadSearchCriteria criteria, int page, int size, String sortBy, String sortDir) {
        return searchLeadsInCategories(criteria, null, page, size, sortBy, sortDir);
    }

    // The operational Leads page: only NEW and VALID-but-unconverted leads
    // (LeadCategory.ACTIVE). Enforced here, in the query itself, AND-ed with
    // every user-supplied filter - so no filter value, page number or count
    // can bring an INVALID or converted lead into this list.
    public Page<Lead> searchActiveLeads(LeadSearchCriteria criteria, int page, int size, String sortBy, String sortDir) {
        return searchLeadsInCategories(criteria, LeadCategory.ACTIVE, page, size, sortBy, sortDir);
    }

    // Report view: the same RBAC-scoped, filterable, sortable, paginated list
    // restricted to the chosen categories (null = all four).
    public Page<Lead> searchLeadsInCategories(
            LeadSearchCriteria criteria, Set<LeadCategory> categories,
            int page, int size, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, LEAD_VIEW);

        Specification<Lead> spec = buildSpecification(criteria, employeeIds, categories);
        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");

        Page<Lead> result = leadRepository.findAll(spec, PageRequest.of(page, size, sort));
        decorateLeads(result.getContent());
        return result;
    }

    // How many leads (within the caller's RBAC scope and the given
    // non-category filters) fall in each category. The four categories are
    // mutually exclusive, so `total` is their exact sum.
    public Map<String, Long> countByCategory(LeadSearchCriteria criteria) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, LEAD_VIEW);

        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        long total = 0;

        for (LeadCategory category : LeadCategory.values()) {

            long n = leadRepository.count(buildSpecification(criteria, employeeIds, java.util.EnumSet.of(category)));
            counts.put(category.name(), n);
            total += n;
        }

        counts.put("TOTAL", total);

        return counts;
    }

    // Number of operational (active) leads in the caller's scope - what the
    // Leads page total and the dashboard card show.
    public long countActiveLeads(List<Long> employeeIds) {
        return leadRepository.count(SpecificationUtil.and(java.util.Arrays.asList(
                LeadSpecifications.ownerIn(employeeIds),
                LeadSpecifications.inCategories(LeadCategory.ACTIVE))));
    }

    // Default export (and "export selected") is the operational list.
    public List<Lead> findForExport(LeadSearchCriteria criteria, List<Long> ids, String sortBy, String sortDir) {
        return findForExport(criteria, ids, sortBy, sortDir, LeadCategory.ACTIVE);
    }

    // Same filter/RBAC scoping as searchLeads, but unpaged (capped) and
    // optionally restricted to an explicit id set - backs the export,
    // including "export only the selected rows". `categories` null = every
    // category (history/reporting); the scope is the narrower of the
    // caller's LEAD_VIEW and LEAD_EXPORT grants.
    public List<Lead> findForExport(
            LeadSearchCriteria criteria, List<Long> ids, String sortBy, String sortDir,
            Set<LeadCategory> categories) {
        return findForExport(criteria, ids, null, sortBy, sortDir, categories);
    }

    // `excludeIds` (used when `ids` is empty): every matching lead except
    // these - the "select all N matching records" selection minus the rows
    // the user unticked. Both lists are AND-ed onto the scoped, filtered
    // query, so neither can bring in a lead outside it. More matching rows
    // than EXPORT_MAX_ROWS is rejected instead of silently truncated.
    public List<Lead> findForExport(
            LeadSearchCriteria criteria, List<Long> ids, List<Long> excludeIds, String sortBy, String sortDir,
            Set<LeadCategory> categories) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveExportEmployeeIds(currentEmployee, LEAD_VIEW, LEAD_EXPORT);

        Specification<Lead> spec = SpecificationUtil.and(java.util.Arrays.asList(
                buildSpecification(criteria, employeeIds, categories),
                ids != null && !ids.isEmpty() ? LeadSpecifications.hasIds(ids) : null,
                LeadSpecifications.notInIds(excludeIds)
        ));

        long matching = leadRepository.count(spec);

        if (matching > EXPORT_MAX_ROWS) {
            throw new IllegalArgumentException("This export would contain " + matching
                    + " leads; at most " + EXPORT_MAX_ROWS + " can be exported at once. Please narrow the filters.");
        }

        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");
        Pageable pageable = PageRequest.of(0, EXPORT_MAX_ROWS, sort);

        List<Lead> leads = leadRepository.findAll(spec, pageable).getContent();

        markConverted(leads);

        activityLogService.log(
                currentEmployee,
                ActivityModule.LEAD, ActivityAction.EXPORT,
                null, null,
                "Exported " + leads.size() + " lead(s)"
                        + (categories == null ? " (all categories)" : " (" + categories + ")")
        );

        return leads;
    }

    // Sets only the `converted` flag (one query per 1,000 leads) - enough to
    // label a lead's category in an export without the heavier UI decoration.
    private void markConverted(List<Lead> leads) {

        List<Long> ids = leads.stream().map(Lead::getId).filter(Objects::nonNull).toList();

        Set<Long> converted = new HashSet<>();

        for (int i = 0; i < ids.size(); i += 1000) {
            converted.addAll(opportunityRepository.findLeadIdsWithOpportunity(
                    ids.subList(i, Math.min(ids.size(), i + 1000))));
        }

        leads.forEach(lead -> lead.setConverted(converted.contains(lead.getId())));
    }

    // Deletes only the requested ids the caller is authorized (LEAD_DELETE)
    // to delete; anything else requested (not found, or outside the
    // caller's scope) is reported back as skipped rather than silently
    // ignored or aborting the whole batch. Each successful delete cascades
    // its full dependent chain - see cascadeDeleteLead.
    @Transactional
    public BulkOperationResult bulkDeleteLeads(List<Long> ids) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, LEAD_DELETE);

        List<Long> succeeded = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();

        for (Long id : ids) {

            Optional<Lead> leadOpt = leadRepository.findById(id);

            if (leadOpt.isEmpty()) {
                skipped.add(id);
                continue;
            }

            Lead lead = leadOpt.get();

            try {

                accessControlService.assertCanAccess(
                        currentEmployee, LEAD_DELETE, lead.getAssignedEmployee().getId());

                cascadeDeleteLead(lead, currentEmployee);
                succeeded.add(id);

            } catch (AccessDeniedException e) {
                skipped.add(id);
            }
        }

        return new BulkOperationResult(succeeded, skipped);
    }

    private Specification<Lead> buildSpecification(LeadSearchCriteria criteria, List<Long> employeeIds) {
        return buildSpecification(criteria, employeeIds, null);
    }

    private Specification<Lead> buildSpecification(
            LeadSearchCriteria criteria, List<Long> employeeIds, Set<LeadCategory> categories) {

        LocalDateTime createdFrom = criteria.getCreatedFrom() != null
                ? criteria.getCreatedFrom().atStartOfDay() : null;

        LocalDateTime createdTo = criteria.getCreatedTo() != null
                ? criteria.getCreatedTo().plusDays(1).atStartOfDay() : null;

        return SpecificationUtil.and(java.util.Arrays.asList(
                LeadSpecifications.fetchAssociations(),
                LeadSpecifications.ownerIn(employeeIds),
                LeadSpecifications.inCategories(categories),
                LeadSpecifications.search(criteria.getSearch()),
                LeadSpecifications.hasStatus(criteria.getStatus()),
                LeadSpecifications.inStatusGroups(criteria.getStatusGroup()),
                Boolean.TRUE.equals(criteria.getInactiveView())
                        ? LeadSpecifications.inactiveView(StaleLeadRules.cutoff(staleAfterMonths)) : null,
                LeadSpecifications.hasLeadSourceId(criteria.getLeadSourceId()),
                LeadSpecifications.hasIndustryId(criteria.getIndustryId()),
                LeadSpecifications.hasProductId(criteria.getProductId()),
                LeadSpecifications.hasBatteryId(criteria.getBatteryId()),
                LeadSpecifications.hasAssignedEmployeeId(criteria.getAssignedEmployeeId()),
                LeadSpecifications.hasCity(criteria.getCity()),
                LeadSpecifications.hasState(criteria.getState()),
                LeadSpecifications.hasLeadValidity(criteria.getLeadValidity()),
                LeadSpecifications.createdBetween(createdFrom, createdTo)
        ));
    }

    public Lead getLeadById(Long id) {

        Lead lead = getAuthorizedLead(id, LEAD_VIEW);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.LEAD, ActivityAction.VIEW,
                lead.getId(), lead.getCompanyName(),
                "Viewed lead"
        );

        decorateLeads(List.of(lead));

        return lead;
    }

    public Lead updateLead(Long id, LeadRequest request) {

        Lead lead = getAuthorizedLead(id, LEAD_MANAGE);

        LeadStatus previousStatus = lead.getLeadStatus();
        String previousRemarks = lead.getFinalRemarks();
        String previousInvalidReason = lead.getInvalidReason();
        LocalDate previousContactDeadline = lead.getContactDeadline();
        String previousAssignee = lead.getAssignedEmployee() == null ? null : lead.getAssignedEmployee().getName();

        // Validate the status rules first so a rejected update leaves the
        // lead completely untouched.
        String newInvalidReason = validateStatusAndReason(
                request.getLeadStatus(), previousStatus, previousInvalidReason, request.getInvalidReason());

        // An existing lead keeps its Product / Battery once it has one (an
        // edit cannot remove the last one), but an older lead that never had
        // one can still be edited without inventing a value.
        requireLineItems(request, !lead.getLeadProducts().isEmpty(), !lead.getLeadBatteries().isEmpty());

        lead.setCompanyName(request.getCompanyName());
        lead.setContactPerson(request.getContactPerson());
        lead.setDesignation(request.getDesignation());
        lead.setPhone(request.getPhone());
        lead.setAlternatePhone(request.getAlternatePhone());
        lead.setEmail(request.getEmail());
        lead.setSecondaryEmail(request.getSecondaryEmail());
        lead.setCity(request.getCity());
        lead.setState(request.getState());
        lead.setPincode(request.getPincode());
        lead.setFinalRemarks(request.getFinalRemarks());

        lead.setIndustry(
                industryRepository.findById(request.getIndustryId())
                        .orElseThrow(() -> new ResourceNotFoundException("Industry not found"))
        );

        lead.setDescription(request.getDescription());
        lead.setLeadStatus(request.getLeadStatus());
        lead.setInvalidReason(newInvalidReason);
        lead.setLeadValidity(validityFor(request.getLeadStatus(), lead.getLeadValidity()));

        // The contact deadline is only replaced when one is supplied, so an
        // older client that does not send it never wipes it.
        if (request.getContactDeadline() != null) {
            lead.setContactDeadline(request.getContactDeadline());
        }

        boolean wasClosed = previousStatus != null && CLOSED_STATUSES.contains(previousStatus);
        boolean isClosed = lead.getLeadStatus() != null && CLOSED_STATUSES.contains(lead.getLeadStatus());

        if (isClosed && !wasClosed) {
            lead.setClosedAt(LocalDateTime.now());
        } else if (!isClosed && wasClosed) {
            lead.setClosedAt(null);
        }

        lead.setLeadSource(
                leadSourceMasterRepository.findById(request.getLeadSourceId())
                        .orElseThrow(() -> new ResourceNotFoundException("Lead Source not found"))
        );

        // Reassignment is its own check: ADMIN (ALL scope) may reassign to
        // anyone, MANAGER (TEAM scope) only to someone within their own
        // team, EMPLOYEE (OWN scope, or no grant) may not reassign at all.
        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (accessControlService.canAssignTo(currentEmployee, LEAD_MANAGE, request.getAssignedEmployeeId())) {

            Employee employee = employeeRepository.findById(request.getAssignedEmployeeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

            lead.setAssignedEmployee(employee);
        }

        applyBatteries(lead, request.getBatteries());
        applyProducts(lead, request.getProducts());

        Lead saved = leadRepository.save(lead);

        // Field-level history (field, old value, new value, actor, time) for
        // the fields the workflow cares about - written only now that the
        // save succeeded. Reassignment is recorded too, so ownership history
        // survives. A save that changed none of them keeps the original
        // generic entry.
        int tracked = 0;

        tracked += logLeadChange(currentEmployee, saved, "Lead status", "leadStatus",
                previousStatus == null ? null : previousStatus.name(),
                saved.getLeadStatus() == null ? null : saved.getLeadStatus().name());
        tracked += logLeadChange(currentEmployee, saved, "Invalid reason", "invalidReason",
                previousInvalidReason, saved.getInvalidReason());
        tracked += logLeadChange(currentEmployee, saved, "Assigned to", "assignedEmployee",
                previousAssignee,
                saved.getAssignedEmployee() == null ? null : saved.getAssignedEmployee().getName());
        tracked += logLeadChange(currentEmployee, saved, "Contact deadline", "contactDeadline",
                previousContactDeadline == null ? null : previousContactDeadline.toString(),
                saved.getContactDeadline() == null ? null : saved.getContactDeadline().toString());

        if (tracked == 0) {

            activityLogService.log(
                    currentEmployee,
                    ActivityModule.LEAD, ActivityAction.UPDATE,
                    saved.getId(), saved.getCompanyName(),
                    "Updated lead"
            );
        }

        if (!Objects.equals(previousRemarks, saved.getFinalRemarks())) {

            activityLogService.log(
                    currentEmployee,
                    ActivityModule.LEAD, ActivityAction.REMARKS_UPDATED,
                    saved.getId(), saved.getCompanyName(),
                    saved.getFinalRemarks()
            );
        }

        decorateLeads(List.of(saved));

        return saved;
    }

    // ---- possible duplicate leads (warning / review aid, never blocks) ----

    private static String normalizePhone(String phone) {

        if (phone == null) {
            return null;
        }

        String digits = phone.replaceAll("[^0-9]", "");

        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }

        return digits.length() == 10 ? digits : null;
    }

    private static String normalizeEmail(String email) {

        String e = trimToNull(email);

        return e != null && e.contains("@") ? e.toLowerCase() : null;
    }

    private static String normalizeCompany(String company) {

        String c = trimToNull(company);

        return c != null && c.length() >= 3 ? c.toLowerCase() : null;
    }

    // Leads that look like the one being entered - same phone, email or
    // company. Only leads the caller may view are described; any others are
    // just counted (hiddenCount) so a duplicate outside their scope is still
    // flagged without leaking its details. Blank / missing inputs are simply
    // not matched on. Nothing is merged, changed or blocked.
    public DuplicateLeadsResponse findPossibleDuplicates(String phone, String email, String company, Long excludeId) {

        String p = normalizePhone(phone);
        String e = normalizeEmail(email);
        String c = normalizeCompany(company);

        if (p == null && e == null && c == null) {
            return new DuplicateLeadsResponse(List.of(), 0);
        }

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, LEAD_VIEW);

        Specification<Lead> any = LeadSpecifications.possibleDuplicateOf(p, e, c, excludeId);
        Specification<Lead> visible = SpecificationUtil.and(java.util.Arrays.asList(
                any, LeadSpecifications.ownerIn(employeeIds)));

        long total = leadRepository.count(any);
        long visibleCount = leadRepository.count(visible);

        List<Lead> found = leadRepository
                .findAll(visible, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();

        // A lead that already has an Opportunity is shown as VALID (same
        // display rule as the lead list), so look up which matches are converted.
        Set<Long> convertedIds = found.isEmpty() ? Set.of()
                : new HashSet<>(opportunityRepository.findLeadIdsWithOpportunity(
                        found.stream().map(Lead::getId).toList()));

        List<DuplicateLeadsResponse.Match> matches = found.stream()
                .map(lead -> {

                    List<String> matchedOn = new ArrayList<>();

                    if (p != null && (p.equals(lead.getPhone()) || p.equals(lead.getAlternatePhone()))) {
                        matchedOn.add("phone");
                    }

                    if (e != null && (e.equals(normalizeEmail(lead.getEmail()))
                            || e.equals(normalizeEmail(lead.getSecondaryEmail())))) {
                        matchedOn.add("email");
                    }

                    if (c != null && lead.getCompanyName() != null
                            && c.equals(lead.getCompanyName().trim().toLowerCase())) {
                        matchedOn.add("company");
                    }

                    return new DuplicateLeadsResponse.Match(
                            lead.getId(), lead.getCompanyName(), lead.getContactPerson(),
                            lead.getPhone(), lead.getEmail(),
                            LeadWorkflow.effectiveStatus(lead.getLeadStatus(), convertedIds.contains(lead.getId())).name(),
                            lead.getAssignedEmployee() == null ? null : lead.getAssignedEmployee().getName(),
                            matchedOn);
                })
                .toList();

        return new DuplicateLeadsResponse(matches, Math.max(0, total - visibleCount));
    }

    private int logLeadChange(Employee actor, Lead lead, String label, String field, String oldValue, String newValue) {

        if (Objects.equals(oldValue, newValue)) {
            return 0;
        }

        activityLogService.logFieldChange(
                actor, ActivityModule.LEAD,
                lead.getId(), lead.getCompanyName(),
                label, field, oldValue, newValue
        );

        return 1;
    }

    private void applyProducts(Lead lead, List<LeadProductRequest> products) {

        lead.getLeadProducts().clear();

        if (products == null) {
            return;
        }

        for (LeadProductRequest item : products) {

            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

            lead.getLeadProducts().add(
                    LeadProduct.builder()
                            .lead(lead)
                            .product(product)
                            .quantity(item.getQuantity())
                            .build()
            );

        }

    }

    private void applyBatteries(Lead lead, List<LeadBatteryRequest> batteries) {

        lead.getLeadBatteries().clear();

        if (batteries == null) {
            return;
        }

        for (LeadBatteryRequest item : batteries) {

            Battery battery = batteryRepository.findById(item.getBatteryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Battery not found"));

            lead.getLeadBatteries().add(
                    LeadBattery.builder()
                            .lead(lead)
                            .battery(battery)
                            .quantity(item.getQuantity())
                            .build()
            );

        }

    }

    // Deletion is a CRM-level operation, not a raw row delete: a Lead that
    // has progressed to an Opportunity (and possibly a Won Customer, and
    // any number of FollowUps against either) is deleted as one atomic
    // chain, in dependency order, so the Admin never has to manually clear
    // out FollowUps/Opportunity/Customer first just to make the Lead
    // deletable. See getDeleteImpact for the preview the frontend shows
    // before this runs.
    @Transactional
    public void deleteLead(Long id) {

        Lead lead = getAuthorizedLead(id, LEAD_DELETE);

        cascadeDeleteLead(lead, currentUserService.getCurrentEmployee());
    }

    // Deletes a Lead's full dependent chain - its Opportunity (which itself
    // cascades to a Won Customer + the Opportunity's FollowUps, via
    // OpportunityService.cascadeDeleteOpportunity), then FollowUps attached
    // directly to the Lead - before deleting the Lead itself. Runs inside
    // the caller's transaction (deleteLead/bulkDeleteLeads above), so any
    // failure partway through rolls back the entire chain.
    private void cascadeDeleteLead(Lead lead, Employee actingEmployee) {

        Optional<Opportunity> opportunity = opportunityRepository.findByLeadId(lead.getId());

        opportunity.ifPresent(opp -> opportunityService.cascadeDeleteOpportunity(opp, actingEmployee));

        for (FollowUp followUp : followUpRepository.findByLeadId(lead.getId())) {

            followUpRepository.delete(followUp);

            activityLogService.log(
                    actingEmployee,
                    ActivityModule.FOLLOWUP, ActivityAction.DELETE,
                    followUp.getId(), lead.getCompanyName(),
                    "Deleted follow-up (cascaded from lead deletion)"
            );
        }

        leadRepository.delete(lead);

        activityLogService.log(
                actingEmployee,
                ActivityModule.LEAD, ActivityAction.DELETE,
                lead.getId(), lead.getCompanyName(),
                "Deleted lead"
        );
    }

    // Backs GET /api/leads/{id}/delete-impact - tells the frontend exactly
    // what deleteLead above will remove, before the Admin commits to it.
    public DeleteImpactResponse getDeleteImpact(Long id) {

        Lead lead = getAuthorizedLead(id, LEAD_DELETE);

        Optional<Opportunity> opportunity = opportunityRepository.findByLeadId(id);

        int followUpCount = followUpRepository.findByLeadId(id).size();
        boolean hasCustomer = false;

        if (opportunity.isPresent()) {
            followUpCount += followUpRepository.findByOpportunityId(opportunity.get().getId()).size();
            hasCustomer = opportunityService.hasCustomer(opportunity.get());
        }

        return new DeleteImpactResponse(
                opportunity.isPresent() ? 1 : 0,
                hasCustomer ? 1 : 0,
                followUpCount,
                hasCustomer
        );
    }

    private Lead getAuthorizedLead(Long id, String permissionCode) {

        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                permissionCode,
                lead.getAssignedEmployee().getId()
        );

        return lead;
    }
}