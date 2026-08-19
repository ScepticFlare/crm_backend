package com.compact.crm.service;

import com.compact.crm.dto.request.LeadBatteryRequest;
import com.compact.crm.dto.request.LeadProductRequest;
import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public Lead createLead(LeadRequest request) {

        Employee employee = employeeRepository.findById(request.getAssignedEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

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
                .leadValidity(LeadValidity.VALID)
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

        return saved;
    }

    // Single reusable list query backing both the main /api/leads endpoint
    // and every status-bucket endpoint (which just force criteria.status
    // before calling this) - combinable search + filters + a whitelisted
    // sort + RBAC scope, all folded into one Specification.
    public Page<Lead> searchLeads(LeadSearchCriteria criteria, int page, int size, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, LEAD_VIEW);

        Specification<Lead> spec = buildSpecification(criteria, employeeIds);
        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");

        return leadRepository.findAll(spec, PageRequest.of(page, size, sort));
    }

    // Same filter/RBAC scoping as searchLeads, but unpaged (capped) and
    // optionally restricted to an explicit id set - backs CSV export,
    // including "export only the selected rows".
    public List<Lead> findForExport(LeadSearchCriteria criteria, List<Long> ids, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, LEAD_EXPORT);
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, LEAD_VIEW);

        Specification<Lead> spec = buildSpecification(criteria, employeeIds);

        if (ids != null && !ids.isEmpty()) {
            Specification<Lead> idSpec = LeadSpecifications.hasIds(ids);
            spec = (spec == null) ? idSpec : spec.and(idSpec);
        }

        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");
        Pageable pageable = PageRequest.of(0, EXPORT_MAX_ROWS, sort);

        List<Lead> leads = leadRepository.findAll(spec, pageable).getContent();

        activityLogService.log(
                currentEmployee,
                ActivityModule.LEAD, ActivityAction.EXPORT,
                null, null,
                "Exported " + leads.size() + " lead(s)"
        );

        return leads;
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

        LocalDateTime createdFrom = criteria.getCreatedFrom() != null
                ? criteria.getCreatedFrom().atStartOfDay() : null;

        LocalDateTime createdTo = criteria.getCreatedTo() != null
                ? criteria.getCreatedTo().plusDays(1).atStartOfDay() : null;

        return SpecificationUtil.and(java.util.Arrays.asList(
                LeadSpecifications.ownerIn(employeeIds),
                LeadSpecifications.search(criteria.getSearch()),
                LeadSpecifications.hasStatus(criteria.getStatus()),
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

        return lead;
    }

    public Lead updateLead(Long id, LeadRequest request) {

        Lead lead = getAuthorizedLead(id, LEAD_MANAGE);

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

        activityLogService.log(
                currentEmployee,
                ActivityModule.LEAD, ActivityAction.UPDATE,
                saved.getId(), saved.getCompanyName(),
                "Updated lead"
        );

        return saved;
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