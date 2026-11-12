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
        return followUp.getRelated().label();
    }

    // The employee who owns a follow-up's record: its lead's assigned employee,
    // or (an opportunity follow-up has no lead_id) its opportunity's lead's.
    // Null when no owner can be resolved - e.g. a historical row whose links
    // are missing. AccessControlService.assertCanAccess then allows only an
    // ALL-scope role, so a broken link can never widen access, and it is never
    // a NullPointerException (500) either.
    private static Long ownerIdOf(Lead lead, Opportunity opportunity) {
        Lead owning = lead != null ? lead : opportunity != null ? opportunity.getLead() : null;
        return owning != null && owning.getAssignedEmployee() != null ? owning.getAssignedEmployee().getId() : null;
    }

    // A follow-up may name a lead, an opportunity, or both - but both must be
    // the SAME record (the opportunity converted from that lead). Otherwise one
    // follow-up would point at two unrelated companies.
    private static void requireConsistentParents(Lead lead, Opportunity opportunity) {
        if (lead != null && opportunity != null
                && (opportunity.getLead() == null || !opportunity.getLead().getId().equals(lead.getId()))) {
            throw new IllegalArgumentException(
                    "The selected lead and opportunity are different records. Choose one of them.");
        }
    }

    // A deactivated activity type is no longer offered for new follow-ups, so
    // it cannot be chosen through the API either. A follow-up that already
    // uses it (`current`) keeps it when edited - nothing is rewritten.
    // A NULL isActive (rows older than the flag) counts as active, as before.
    private static void requireSelectableActivityType(ActivityType chosen, ActivityType current) {

        boolean keepsCurrent = current != null && current.getId().equals(chosen.getId());

        if (Boolean.FALSE.equals(chosen.getIsActive()) && !keepsCurrent) {
            throw new IllegalArgumentException(
                    "Activity type \"" + chosen.getName() + "\" is inactive and cannot be selected.");
        }
    }

    // A PENDING follow-up on an Opportunity IS that opportunity's next action,
    // so it has to say what the action is (remarks) - otherwise it could never
    // satisfy the "active opportunity needs a next action" rule and the user
    // would be left with a follow-up that does not count. Lead-level
    // follow-ups (contact attempts) and COMPLETED / CANCELLED / MISSED ones
    // are unaffected.
    private static void requireNextActionIfOpportunityFollowUp(Opportunity opportunity, FollowUpRequest request) {

        if (opportunity != null
                && request.getStatus() == FollowUpStatus.PENDING
                && !FollowUpRules.hasDescription(request.getRemarks())) {

            throw new IllegalArgumentException(FollowUpRules.DESCRIPTION_REQUIRED_MESSAGE);
        }
    }

    public FollowUp createFollowUp(FollowUpRequest request) {

        if (request.getLeadId() == null && request.getOpportunityId() == null) {
            throw new ResourceNotFoundException(NO_PARENT_MESSAGE);
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

        assertCanManageParents(currentEmployee, lead, opportunity);

        // after authorization, so nothing is revealed about records out of scope
        requireConsistentParents(lead, opportunity);

        Employee employee = employeeRepository.findById(request.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        ActivityType activityType = activityTypeRepository
                .findById(request.getActivityTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Activity Type not found"));

        requireSelectableActivityType(activityType, null);

        requireNextActionIfOpportunityFollowUp(opportunity, request);

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
        return findForExport(criteria, ids, null, sortBy, sortDir);
    }

    // Same scope and filters as the list. `ids` = exactly those rows;
    // otherwise every matching row minus `excludeIds` (the "select all N
    // matching records" selection). Both are AND-ed onto the scoped query.
    public List<FollowUp> findForExport(
            FollowUpSearchCriteria criteria, List<Long> ids, List<Long> excludeIds, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, FOLLOWUP_EXPORT);
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, FOLLOWUP_VIEW);

        Specification<FollowUp> spec = SpecificationUtil.and(java.util.Arrays.asList(
                buildSpecification(criteria, employeeIds),
                ids != null && !ids.isEmpty() ? FollowUpSpecifications.hasIds(ids) : null,
                FollowUpSpecifications.notInIds(excludeIds)
        ));

        long matching = followUpRepository.count(spec);

        if (matching > EXPORT_MAX_ROWS) {
            throw new IllegalArgumentException("This export would contain " + matching
                    + " follow-ups; at most " + EXPORT_MAX_ROWS + " can be exported at once. Please narrow the filters.");
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

            try {

                accessControlService.assertCanAccess(currentEmployee, FOLLOWUP_DELETE,
                        ownerIdOf(followUp.getLead(), followUp.getOpportunity()));
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
                FollowUpSpecifications.fetchAssociations(),
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

        decorateForEdit(followUp);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.FOLLOWUP, ActivityAction.VIEW,
                followUp.getId(), followUpEntityName(followUp),
                "Viewed follow-up"
        );

        return followUp;
    }

    // Transactional: completing a follow-up and scheduling the next one are
    // saved together or not at all.
    @Transactional
    public FollowUp updateFollowUp(Long id, FollowUpRequest request) {

        FollowUp followUp = getAuthorizedFollowUp(id, FOLLOWUP_MANAGE);

        // Captured before mutation so the write path below can tell a plain
        // field edit from a genuine PENDING/... -> COMPLETED transition -
        // there's no separate "complete" endpoint today, so this diff is
        // the only way to log FOLLOWUP.COMPLETE distinctly from UPDATE.
        FollowUpStatus previousStatus = followUp.getStatus();

        if (request.getLeadId() == null && request.getOpportunityId() == null) {
            throw new IllegalArgumentException(NO_PARENT_MESSAGE);
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

        assertCanManageParents(currentUserService.getCurrentEmployee(), lead, opportunity);

        requireConsistentParents(lead, opportunity);

        Employee employee = employeeRepository.findById(request.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        ActivityType activityType = activityTypeRepository.findById(request.getActivityTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Activity Type not found"));

        requireSelectableActivityType(activityType, followUp.getActivityType());

        requireNextActionIfOpportunityFollowUp(opportunity, request);

        boolean justCompleted = previousStatus != FollowUpStatus.COMPLETED
                && request.getStatus() == FollowUpStatus.COMPLETED;

        // Decided (and rejected) before anything is changed.
        FollowUpRequest next = justCompleted ? nextAfterCompletion(followUp, lead, opportunity, request) : null;

        followUp.setLead(lead);
        followUp.setOpportunity(opportunity);
        followUp.setEmployee(employee);
        followUp.setActivityType(activityType);
        followUp.setStatus(request.getStatus());
        followUp.setScheduledDate(request.getScheduledDate());
        followUp.setCompletedDate(request.getCompletedDate());
        followUp.setRemarks(request.getRemarks());

        FollowUp saved = followUpRepository.save(followUp);

        if (next != null) {
            createFollowUp(next);
        }

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.FOLLOWUP,
                justCompleted ? ActivityAction.COMPLETE : ActivityAction.UPDATE,
                saved.getId(), followUpEntityName(saved),
                justCompleted ? "Completed follow-up" : "Updated follow-up"
        );

        return saved;
    }

    // Completing a follow-up must not take its opportunity off the radar: an
    // opportunity that still needs a next follow-up (active or POSTPONED - see
    // OpportunityWorkflow.requiresNextFollowUp) and has no OTHER qualifying
    // one must get the next follow-up with this request, or the completion is
    // rejected. A lead's follow-up counts for the opportunity converted from
    // that lead, so completing it is checked the same way. Returns the
    // follow-up to create (null: nothing to create). Nothing is duplicated:
    // when an identical pending follow-up (same opportunity, date and next
    // action - e.g. a resubmitted form) already exists, nothing is created,
    // and nothing is asked for when another qualifying one exists.
    private FollowUpRequest nextAfterCompletion(
            FollowUp completing, Lead lead, Opportunity opportunity, FollowUpRequest request) {

        Opportunity target = opportunity != null ? opportunity
                : lead == null ? null : opportunityRepository.findByLeadId(lead.getId()).orElse(null);

        if (target == null || !OpportunityWorkflow.requiresNextFollowUp(target)) {
            return null;
        }

        String description = request.getNextActionDescription();
        LocalDateTime when = request.getNextFollowUpDate();
        Long activityTypeId = request.getNextActionActivityTypeId();

        boolean supplied = (description != null && !description.isBlank()) || when != null || activityTypeId != null;

        if (!supplied) {

            if (!hasOtherQualifyingFollowUp(target, completing.getId())) {
                throw new IllegalArgumentException(COMPLETION_NEEDS_NEXT_MESSAGE);
            }

            return null;
        }

        if (!FollowUpRules.hasDescription(description)) {
            throw new IllegalArgumentException(FollowUpRules.DESCRIPTION_REQUIRED_MESSAGE);
        }

        if (when == null || activityTypeId == null) {
            throw new IllegalArgumentException(
                    "Please give the next action together with its follow-up date and activity type.");
        }

        if (when.isBefore(com.compact.crm.util.BusinessClock.now())) {
            throw new IllegalArgumentException("The next follow-up date must be now or in the future.");
        }

        String remarks = description.trim();

        boolean alreadyScheduled = followUpRepository.findByOpportunityId(target.getId()).stream()
                .anyMatch(f -> !f.getId().equals(completing.getId())
                        && f.getStatus() == FollowUpStatus.PENDING
                        && when.equals(f.getScheduledDate())
                        && f.getRemarks() != null && remarks.equals(f.getRemarks().trim()));

        if (alreadyScheduled) {
            return null;
        }

        FollowUpRequest next = new FollowUpRequest();
        next.setOpportunityId(target.getId());
        next.setEmployeeId(request.getEmployeeId());
        next.setActivityTypeId(activityTypeId);
        next.setStatus(FollowUpStatus.PENDING);
        next.setScheduledDate(when);
        next.setRemarks(remarks);

        return next;
    }

    static final String COMPLETION_NEEDS_NEXT_MESSAGE =
            "This opportunity has no other upcoming follow-up. Enter the next follow-up date and time, "
                    + "activity type and next action to complete this one.";

    // A qualifying follow-up (FollowUpRules: pending, future, described) on
    // the opportunity or its originating lead, other than `excludeId`.
    private boolean hasOtherQualifyingFollowUp(Opportunity opportunity, Long excludeId) {
        return earliestOtherQualifyingDate(opportunity, excludeId).isPresent();
    }

    // Scheduled date of the earliest such follow-up (same candidates as above).
    private Optional<LocalDateTime> earliestOtherQualifyingDate(Opportunity opportunity, Long excludeId) {

        LocalDateTime now = com.compact.crm.util.BusinessClock.now();

        List<FollowUp> candidates = new ArrayList<>(followUpRepository.findByOpportunityId(opportunity.getId()));

        if (opportunity.getLead() != null) {
            candidates.addAll(followUpRepository.findByLeadId(opportunity.getLead().getId()));
        }

        return candidates.stream()
                .filter(f -> !f.getId().equals(excludeId) && FollowUpRules.qualifies(f, now))
                .map(FollowUp::getScheduledDate)
                .min(java.util.Comparator.naturalOrder());
    }

    // Fills the read-only fields the edit form needs, for the single
    // follow-up response only: whether its lead has been converted (so the
    // form can label a lead that is no longer in the active Leads list), and
    // what completing it will require - decided by the same target
    // opportunity and the same qualifying rule updateFollowUp enforces, so the
    // form never under- or over-asks. Nothing is written.
    private void decorateForEdit(FollowUp followUp) {

        Lead lead = followUp.getLead();

        Opportunity convertedInto = lead == null ? null
                : opportunityRepository.findByLeadId(lead.getId()).orElse(null);

        if (lead != null) {
            lead.setConverted(convertedInto != null);
        }

        Opportunity target = followUp.getOpportunity() != null ? followUp.getOpportunity() : convertedInto;

        boolean needsNext = target != null && OpportunityWorkflow.requiresNextFollowUp(target);

        followUp.setNextFollowUpNeededOnCompletion(needsNext);
        followUp.setOtherNextFollowUpDate(
                needsNext ? earliestOtherQualifyingDate(target, followUp.getId()).orElse(null) : null);
    }

    static final String NO_PARENT_MESSAGE = "FollowUp must belong to either a Lead or an Opportunity";

    // A follow-up may name a Lead, an Opportunity, or (historically) both.
    // The caller must be allowed to manage follow-ups of EVERY record it
    // names - checking only one would let a request attach a follow-up to an
    // out-of-scope record by also naming one the caller owns. A record's
    // owner is its Lead's assigned employee (an Opportunity's via its Lead),
    // the same resolution getAuthorizedFollowUp and the list scope use.
    private void assertCanManageParents(Employee actor, Lead lead, Opportunity opportunity) {

        if (lead != null) {
            accessControlService.assertCanAccess(actor, FOLLOWUP_MANAGE, ownerIdOf(lead, null));
        }

        if (opportunity != null) {
            accessControlService.assertCanAccess(actor, FOLLOWUP_MANAGE, ownerIdOf(null, opportunity));
        }
    }

    public List<FollowUp> getFollowUpsByLead(Long leadId) {

        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                FOLLOWUP_VIEW,
                ownerIdOf(lead, null)
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
                ownerIdOf(null, opportunity)
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

        accessControlService.assertCanAccess(currentEmployee, permissionCode,
                ownerIdOf(followUp.getLead(), followUp.getOpportunity()));

        return followUp;
    }
}