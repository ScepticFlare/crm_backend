package com.compact.crm.service;

import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.search.OpportunitySearchCriteria;
import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.specification.OpportunitySpecifications;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_DELETE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_EXPORT;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;

@Service
@RequiredArgsConstructor
public class OpportunityService {

    private final OpportunityRepository opportunityRepository;
    private final LeadRepository leadRepository;
    private final CustomerRepository customerRepository;
    private final FollowUpRepository followUpRepository;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;
    private final SalesStageService salesStageService;
    private final ActivityLogService activityLogService;

    private static final String WON_STAGE_NAME = "WON";

    // Still enforced on UPDATE (see updateOpportunity) - editing a WON
    // opportunity's stage away from WON while a Customer conversion already
    // exists would silently orphan that Customer's link. DELETE no longer
    // uses this: deleting a WON+converted Opportunity is now an explicit,
    // previewed cascade (see cascadeDeleteOpportunity/getDeleteImpact)
    // rather than being blocked outright.
    private static final String WON_LOCK_MESSAGE =
            "This opportunity has already been converted to a customer and cannot be moved out of Won.";

    // The "In Progress" bucket is everything not in one of these closed/
    // terminal stages - same exclusion list the old searchInProgressOpportunities
    // query used.
    private static final List<String> CLOSED_STAGE_NAMES =
            List.of("WON", "LOST", "POSTPONED", "DROPPED", "UNRESPONSIVE", "INVALID");

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "title", "title",
            "value", "productValue",
            "closingDate", "expectedClosingDate",
            "createdDate", "createdAt",
            "stage", "salesStage.name",
            "assignedEmployee", "lead.assignedEmployee.name"
    );

    private static final int EXPORT_MAX_ROWS = 20_000;

    /**
     * Convert a Lead into an Opportunity.
     */
    public Opportunity convertLeadToOpportunity(Long leadId, OpportunityRequest request) {

        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                LEAD_MANAGE,
                lead.getAssignedEmployee().getId()
        );

        // Prevent duplicate conversion
        if (opportunityRepository.existsByLeadId(leadId)) {
            throw new RuntimeException("This lead has already been converted into an Opportunity.");
        }

        SalesStage salesStage = salesStageService.getByName(request.getSalesStage());

        Opportunity opportunity = Opportunity.builder()
                .title(request.getTitle())
                .productValue(request.getProductValue())
                .expectedClosingDate(request.getExpectedClosingDate())
                .salesStage(salesStage)
                .leadValidity(request.getLeadValidity())
                .lead(lead)
                .build();

        lead.setLeadValidity(request.getLeadValidity());
        leadRepository.save(lead);

        Opportunity saved = opportunityRepository.save(opportunity);

        activityLogService.log(
                currentEmployee,
                ActivityModule.LEAD, ActivityAction.CONVERT,
                lead.getId(), lead.getCompanyName(),
                "Converted lead to opportunity: " + saved.getTitle()
        );

        return saved;
    }

    // Reusable list query backing the main /api/opportunities endpoint and
    // every stage-bucket endpoint (postponed/dropped/lost/unresponsive/
    // invalid force criteria.stageName; in-progress uses the exclusion
    // list instead via searchInProgress below).
    public Page<Opportunity> searchOpportunities(
            OpportunitySearchCriteria criteria, int page, int size, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, OPPORTUNITY_VIEW);

        Specification<Opportunity> spec = buildSpecification(criteria, employeeIds);
        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");

        return opportunityRepository.findAll(spec, PageRequest.of(page, size, sort));
    }

    public Page<Opportunity> searchInProgressOpportunities(
            OpportunitySearchCriteria criteria, int page, int size, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, OPPORTUNITY_VIEW);

        Specification<Opportunity> spec = SpecificationUtil.and(java.util.Arrays.asList(
                buildSpecification(criteria, employeeIds),
                OpportunitySpecifications.excludingStageNames(CLOSED_STAGE_NAMES)
        ));

        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");

        return opportunityRepository.findAll(spec, PageRequest.of(page, size, sort));
    }

    public List<Opportunity> findForExport(
            OpportunitySearchCriteria criteria, List<Long> ids, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, OPPORTUNITY_EXPORT);
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, OPPORTUNITY_VIEW);

        Specification<Opportunity> spec = buildSpecification(criteria, employeeIds);

        if (ids != null && !ids.isEmpty()) {
            Specification<Opportunity> idSpec = OpportunitySpecifications.hasIds(ids);
            spec = (spec == null) ? idSpec : spec.and(idSpec);
        }

        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");
        Pageable pageable = PageRequest.of(0, EXPORT_MAX_ROWS, sort);

        List<Opportunity> opportunities = opportunityRepository.findAll(spec, pageable).getContent();

        activityLogService.log(
                currentEmployee,
                ActivityModule.OPPORTUNITY, ActivityAction.EXPORT,
                null, null,
                "Exported " + opportunities.size() + " opportunity(ies)"
        );

        return opportunities;
    }

    @Transactional
    public BulkOperationResult bulkDeleteOpportunities(List<Long> ids) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, OPPORTUNITY_DELETE);

        List<Long> succeeded = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();

        for (Long id : ids) {

            Optional<Opportunity> opportunityOpt = opportunityRepository.findById(id);

            if (opportunityOpt.isEmpty()) {
                skipped.add(id);
                continue;
            }

            Opportunity opportunity = opportunityOpt.get();

            try {

                accessControlService.assertCanAccess(
                        currentEmployee, OPPORTUNITY_DELETE, opportunity.getLead().getAssignedEmployee().getId());

                cascadeDeleteOpportunity(opportunity, currentEmployee);
                succeeded.add(id);

            } catch (AccessDeniedException e) {
                skipped.add(id);
            }
        }

        return new BulkOperationResult(succeeded, skipped);
    }

    private Specification<Opportunity> buildSpecification(OpportunitySearchCriteria criteria, List<Long> employeeIds) {

        LocalDateTime createdFrom = criteria.getCreatedFrom() != null
                ? criteria.getCreatedFrom().atStartOfDay() : null;

        LocalDateTime createdTo = criteria.getCreatedTo() != null
                ? criteria.getCreatedTo().plusDays(1).atStartOfDay() : null;

        return SpecificationUtil.and(java.util.Arrays.asList(
                OpportunitySpecifications.ownerIn(employeeIds),
                OpportunitySpecifications.search(criteria.getSearch()),
                OpportunitySpecifications.hasStageName(criteria.getStageName()),
                OpportunitySpecifications.hasSalesStageId(criteria.getSalesStageId()),
                OpportunitySpecifications.hasAssignedEmployeeId(criteria.getAssignedEmployeeId()),
                OpportunitySpecifications.hasIndustryId(criteria.getIndustryId()),
                OpportunitySpecifications.hasProductId(criteria.getProductId()),
                OpportunitySpecifications.hasLeadValidity(criteria.getLeadValidity()),
                OpportunitySpecifications.createdBetween(createdFrom, createdTo),
                OpportunitySpecifications.expectedClosingBetween(
                        criteria.getExpectedClosingFrom(), criteria.getExpectedClosingTo()),
                OpportunitySpecifications.valueBetween(criteria.getMinValue(), criteria.getMaxValue())
        ));
    }

    public Opportunity getOpportunityById(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_VIEW);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.OPPORTUNITY, ActivityAction.VIEW,
                opportunity.getId(), opportunity.getTitle(),
                "Viewed opportunity"
        );

        return opportunity;
    }

    public Opportunity updateOpportunity(Long id, OpportunityRequest request) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_MANAGE);

        boolean isCurrentlyWon =
                WON_STAGE_NAME.equalsIgnoreCase(opportunity.getSalesStage().getName());

        boolean isLeavingWon =
                isCurrentlyWon && !WON_STAGE_NAME.equalsIgnoreCase(request.getSalesStage());

        if (isLeavingWon && customerRepository.findByOpportunity(opportunity).isPresent()) {
            throw new IllegalArgumentException(WON_LOCK_MESSAGE);
        }

        SalesStage salesStage = salesStageService.findOrCreate(request.getSalesStage());

        opportunity.setTitle(request.getTitle());
        opportunity.setProductValue(request.getProductValue());
        opportunity.setExpectedClosingDate(request.getExpectedClosingDate());
        opportunity.setSalesStage(salesStage);
        opportunity.setLeadValidity(request.getLeadValidity());

        opportunity.getLead().setLeadValidity(request.getLeadValidity());
        leadRepository.save(opportunity.getLead());

        Opportunity saved = opportunityRepository.save(opportunity);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.OPPORTUNITY, ActivityAction.UPDATE,
                saved.getId(), saved.getTitle(),
                "Updated opportunity"
        );

        return saved;
    }

    @Transactional
    public void deleteOpportunity(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_DELETE);

        cascadeDeleteOpportunity(opportunity, currentUserService.getCurrentEmployee());
    }

    // Deletes an Opportunity's dependents - its Won Customer conversion (if
    // any) and every FollowUp attached to it - then the Opportunity itself,
    // in that order, so no foreign-key constraint on either child table is
    // ever violated. Runs inside the caller's transaction (deleteOpportunity
    // above, or LeadService.deleteLead cascading one level further down the
    // Lead -> Opportunity -> Customer/FollowUps chain) so a failure at any
    // step rolls the whole delete back - no partially-deleted chain.
    //
    // Deliberately package-private and takes an already-authorized
    // Opportunity + the acting Employee rather than re-running
    // getAuthorizedOpportunity/OPPORTUNITY_DELETE itself: the caller has
    // already made the one authorization decision that matters (can this
    // employee delete this top-level record), and a dependent record being
    // technically outside that same TEAM/OWN scope must never turn a
    // legitimate cascade into an unexpected 403 partway through.
    void cascadeDeleteOpportunity(Opportunity opportunity, Employee actingEmployee) {

        Optional<Customer> customer = customerRepository.findByOpportunity(opportunity);

        if (customer.isPresent()) {

            customerRepository.delete(customer.get());

            activityLogService.log(
                    actingEmployee,
                    ActivityModule.CUSTOMER, ActivityAction.DELETE,
                    customer.get().getId(), customer.get().getCompanyName(),
                    "Deleted customer (cascaded from opportunity deletion)"
            );
        }

        List<FollowUp> followUps = followUpRepository.findByOpportunityId(opportunity.getId());

        for (FollowUp followUp : followUps) {

            followUpRepository.delete(followUp);

            activityLogService.log(
                    actingEmployee,
                    ActivityModule.FOLLOWUP, ActivityAction.DELETE,
                    followUp.getId(), opportunity.getTitle(),
                    "Deleted follow-up (cascaded from opportunity deletion)"
            );
        }

        opportunityRepository.delete(opportunity);

        activityLogService.log(
                actingEmployee,
                ActivityModule.OPPORTUNITY, ActivityAction.DELETE,
                opportunity.getId(), opportunity.getTitle(),
                "Deleted opportunity"
        );
    }

    // Small delegation point so LeadService.getDeleteImpact can tell
    // whether a Lead's Opportunity has a Won Customer conversion without
    // LeadService needing its own CustomerRepository dependency - Customer
    // lookups by Opportunity stay owned by this service.
    public boolean hasCustomer(Opportunity opportunity) {
        return customerRepository.findByOpportunity(opportunity).isPresent();
    }

    // Backs GET /api/opportunities/{id}/delete-impact - tells the frontend
    // exactly what deleteOpportunity/cascadeDeleteOpportunity above will
    // remove, before the Admin commits to it.
    public DeleteImpactResponse getDeleteImpact(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_DELETE);

        boolean hasCustomer = customerRepository.findByOpportunity(opportunity).isPresent();
        int followUpCount = followUpRepository.findByOpportunityId(opportunity.getId()).size();

        return new DeleteImpactResponse(0, hasCustomer ? 1 : 0, followUpCount, hasCustomer);
    }

    private Opportunity getAuthorizedOpportunity(Long id, String permissionCode) {

        Opportunity opportunity = opportunityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                permissionCode,
                opportunity.getLead().getAssignedEmployee().getId()
        );

        return opportunity;
    }
}