package com.compact.crm.service;

import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.search.FollowUpSearchCriteria;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.specification.FollowUpSpecifications;
import com.compact.crm.specification.SpecificationUtil;
import com.compact.crm.util.SortWhitelist;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.FOLLOWUP_DELETE;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_EXPORT;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_MANAGE;
import static com.compact.crm.security.AccessControlService.FOLLOWUP_VIEW;

@Service
@RequiredArgsConstructor
public class FollowUpService {

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "scheduledDate", "scheduledDate",
            "status", "status",
            "activityType", "activityType.name",
            "employee", "employee.name",
            "createdDate", "createdAt"
    );

    private static final int EXPORT_MAX_ROWS = 20_000;

    private final FollowUpRepository followUpRepository;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final EmployeeRepository employeeRepository;
    private final ActivityTypeRepository activityTypeRepository;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;
    private final ActivityLogService activityLogService;

    // FollowUp has no title of its own - fall back to whichever parent
    // (Lead or Opportunity) it belongs to, matching how the FollowUps list
    // UI already identifies a row.
    private String followUpEntityName(FollowUp followUp) {
        if (followUp.getLead() != null) {
            return followUp.getLead().getCompanyName();
        }
        if (followUp.getOpportunity() != null) {
            return followUp.getOpportunity().getTitle();
        }
        return null;
    }

    public FollowUp createFollowUp(FollowUpRequest request) {

        if (request.getLeadId() == null && request.getOpportunityId() == null) {
            throw new ResourceNotFoundException(
                    "FollowUp must belong to either a Lead or an Opportunity");
        }

        Lead lead = null;
        Opportunity opportunity = null;

        if (request.getLeadId() != null) {
            lead = leadRepository.findById(request.getLeadId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));
        }

        if (request.getOpportunityId() != null) {
            opportunity = opportunityRepository.findById(request.getOpportunityId())
                    .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));
        }

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Employee parentOwner = lead != null
                ? lead.getAssignedEmployee()
                : opportunity.getLead().getAssignedEmployee();

        accessControlService.assertCanAccess(currentEmployee, FOLLOWUP_MANAGE, parentOwner.getId());

        Employee employee = employeeRepository.findById(request.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        ActivityType activityType = activityTypeRepository
                .findById(request.getActivityTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Activity Type not found"));

        FollowUp followUp = FollowUp.builder()
                .lead(lead)
                .opportunity(opportunity)
                .employee(employee)
                .activityType(activityType)
                .status(request.getStatus())
                .scheduledDate(request.getScheduledDate())
                .completedDate(request.getCompletedDate())
                .remarks(request.getRemarks())
                .build();

        FollowUp saved = followUpRepository.save(followUp);

        activityLogService.log(
                currentEmployee,
                ActivityModule.FOLLOWUP, ActivityAction.CREATE,
                saved.getId(), followUpEntityName(saved),
                "Created follow-up"
        );

        return saved;
    }
    public Page<FollowUp> searchFollowUps(
            FollowUpSearchCriteria criteria, int page, int size, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, FOLLOWUP_VIEW);

        Specification<FollowUp> spec = buildSpecification(criteria, employeeIds);
        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "scheduledDate");

        return followUpRepository.findAll(spec, PageRequest.of(page, size, sort));
    }

    public List<FollowUp> findForExport(
            FollowUpSearchCriteria criteria, List<Long> ids, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, FOLLOWUP_EXPORT);
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, FOLLOWUP_VIEW);

        Specification<FollowUp> spec = buildSpecification(criteria, employeeIds);

        if (ids != null && !ids.isEmpty()) {
            Specification<FollowUp> idSpec = FollowUpSpecifications.hasIds(ids);
            spec = (spec == null) ? idSpec : spec.and(idSpec);
        }

        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "scheduledDate");
        Pageable pageable = PageRequest.of(0, EXPORT_MAX_ROWS, sort);

        List<FollowUp> followUps = followUpRepository.findAll(spec, pageable).getContent();

        activityLogService.log(
                currentEmployee,
                ActivityModule.FOLLOWUP, ActivityAction.EXPORT,
                null, null,
                "Exported " + followUps.size() + " follow-up(s)"
        );

        return followUps;
    }

    public BulkOperationResult bulkDeleteFollowUps(List<Long> ids) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, FOLLOWUP_DELETE);

        List<Long> succeeded = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();

        for (Long id : ids) {

            Optional<FollowUp> followUpOpt = followUpRepository.findById(id);

            if (followUpOpt.isEmpty()) {
                skipped.add(id);
                continue;
            }

            FollowUp followUp = followUpOpt.get();

            Employee owner = followUp.getLead() != null
                    ? followUp.getLead().getAssignedEmployee()
                    : followUp.getOpportunity().getLead().getAssignedEmployee();

            try {

                accessControlService.assertCanAccess(currentEmployee, FOLLOWUP_DELETE, owner.getId());
                followUpRepository.delete(followUp);
                succeeded.add(id);

                activityLogService.log(
                        currentEmployee,
                        ActivityModule.FOLLOWUP, ActivityAction.DELETE,
                        followUp.getId(), followUpEntityName(followUp),
                        "Deleted follow-up"
                );

            } catch (AccessDeniedException e) {
                skipped.add(id);
            }
        }

        return new BulkOperationResult(succeeded, skipped);
    }

    private Specification<FollowUp> buildSpecification(FollowUpSearchCriteria criteria, List<Long> employeeIds) {

        LocalDateTime scheduledFrom = criteria.getScheduledFrom() != null
                ? criteria.getScheduledFrom().atStartOfDay() : null;

        LocalDateTime scheduledTo = criteria.getScheduledTo() != null
                ? criteria.getScheduledTo().plusDays(1).atStartOfDay() : null;

        return SpecificationUtil.and(java.util.Arrays.asList(
                FollowUpSpecifications.ownerIn(employeeIds),
                FollowUpSpecifications.search(criteria.getSearch()),
                FollowUpSpecifications.hasStatus(criteria.getStatus()),
                FollowUpSpecifications.hasActivityTypeId(criteria.getActivityTypeId()),
                FollowUpSpecifications.hasEmployeeId(criteria.getAssignedEmployeeId()),
                FollowUpSpecifications.hasLeadId(criteria.getLeadId()),
                FollowUpSpecifications.hasOpportunityId(criteria.getOpportunityId()),
                FollowUpSpecifications.scheduledBetween(scheduledFrom, scheduledTo)
        ));
    }

    public FollowUp getFollowUpById(Long id) {

        FollowUp followUp = getAuthorizedFollowUp(id, FOLLOWUP_VIEW);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.FOLLOWUP, ActivityAction.VIEW,
                followUp.getId(), followUpEntityName(followUp),
                "Viewed follow-up"
        );

        return followUp;
    }

    public FollowUp updateFollowUp(Long id, FollowUpRequest request) {

        FollowUp followUp = getAuthorizedFollowUp(id, FOLLOWUP_MANAGE);

        // Captured before mutation so the write path below can tell a plain
        // field edit from a genuine PENDING/... -> COMPLETED transition -
        // there's no separate "complete" endpoint today, so this diff is
        // the only way to log FOLLOWUP.COMPLETE distinctly from UPDATE.
        FollowUpStatus previousStatus = followUp.getStatus();

        Lead lead = null;
        Opportunity opportunity = null;

        if (request.getLeadId() != null) {
            lead = leadRepository.findById(request.getLeadId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));
        }

        if (request.getOpportunityId() != null) {
            opportunity = opportunityRepository.findById(request.getOpportunityId())
                    .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));
        }

        Employee newParentOwner = lead != null
                ? lead.getAssignedEmployee()
                : opportunity.getLead().getAssignedEmployee();

        accessControlService.assertCanAccess(
                currentUserService.getCurrentEmployee(),
                FOLLOWUP_MANAGE,
                newParentOwner.getId()
        );

        Employee employee = employeeRepository.findById(request.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        ActivityType activityType = activityTypeRepository.findById(request.getActivityTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Activity Type not found"));

        followUp.setLead(lead);
        followUp.setOpportunity(opportunity);
        followUp.setEmployee(employee);
        followUp.setActivityType(activityType);
        followUp.setStatus(request.getStatus());
        followUp.setScheduledDate(request.getScheduledDate());
        followUp.setCompletedDate(request.getCompletedDate());
        followUp.setRemarks(request.getRemarks());

        FollowUp saved = followUpRepository.save(followUp);

        boolean justCompleted = previousStatus != FollowUpStatus.COMPLETED
                && saved.getStatus() == FollowUpStatus.COMPLETED;

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.FOLLOWUP,
                justCompleted ? ActivityAction.COMPLETE : ActivityAction.UPDATE,
                saved.getId(), followUpEntityName(saved),
                justCompleted ? "Completed follow-up" : "Updated follow-up"
        );

        return saved;
    }

    public List<FollowUp> getFollowUpsByLead(Long leadId) {

        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                FOLLOWUP_VIEW,
                lead.getAssignedEmployee().getId()
        );

        return followUpRepository.findByLeadId(leadId);
    }

    public List<FollowUp> getFollowUpsByOpportunity(Long opportunityId) {

        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                FOLLOWUP_VIEW,
                opportunity.getLead().getAssignedEmployee().getId()
        );

        return followUpRepository.findByOpportunityId(opportunityId);
    }

    public void deleteFollowUp(Long id) {

        FollowUp followUp = getAuthorizedFollowUp(id, FOLLOWUP_DELETE);

        followUpRepository.delete(followUp);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.FOLLOWUP, ActivityAction.DELETE,
                followUp.getId(), followUpEntityName(followUp),
                "Deleted follow-up"
        );
    }

    private FollowUp getAuthorizedFollowUp(Long id, String permissionCode) {

        FollowUp followUp = followUpRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FollowUp not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Employee owner;

        if (followUp.getLead() != null) {
            owner = followUp.getLead().getAssignedEmployee();
        } else {
            owner = followUp.getOpportunity()
                    .getLead()
                    .getAssignedEmployee();
        }

        accessControlService.assertCanAccess(currentEmployee, permissionCode, owner.getId());

        return followUp;
    }
}