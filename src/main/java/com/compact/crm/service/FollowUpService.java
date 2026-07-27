package com.compact.crm.service;

import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.Role;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FollowUpService {

    private final FollowUpRepository followUpRepository;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final EmployeeRepository employeeRepository;
    private final ActivityTypeRepository activityTypeRepository;
    private final CurrentUserService currentUserService;

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

        if (currentEmployee.getRole() != Role.ADMIN) {

            Employee owner;

            if (lead != null) {
                owner = lead.getAssignedEmployee();
            } else {
                owner = opportunity.getLead().getAssignedEmployee();
            }

            if (!owner.getId().equals(currentEmployee.getId())) {
                throw new AccessDeniedException(
                        "You are not authorized to create follow-ups for this record.");
            }
        }

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

        return followUpRepository.save(followUp);
    }
    public Page<FollowUp> getAllFollowUps(int page, int size, String search) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Pageable pageable = PageRequest.of(page, size);

        if (search == null) {
            search = "";
        }

        if (currentEmployee.getRole() == Role.ADMIN) {

            return followUpRepository.searchFollowUps(
                    null,
                    search,
                    pageable
            );

        }

        return followUpRepository.searchFollowUps(
                currentEmployee,
                search,
                pageable
        );

    }

    public FollowUp getFollowUpById(Long id) {

        return getAuthorizedFollowUp(id);
    }

    public FollowUp updateFollowUp(Long id, FollowUpRequest request) {

        FollowUp followUp = getAuthorizedFollowUp(id);

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

        if (currentUserService.getCurrentEmployee().getRole() != Role.ADMIN) {

            Employee owner;

            if (lead != null) {
                owner = lead.getAssignedEmployee();
            } else {
                owner = opportunity.getLead().getAssignedEmployee();
            }

            if (!owner.getId().equals(currentUserService.getCurrentEmployee().getId())) {
                throw new AccessDeniedException(
                        "You are not authorized to move this follow-up.");
            }
        }

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

        return followUpRepository.save(followUp);
    }

    public List<FollowUp> getFollowUpsByLead(Long leadId) {

        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN &&
                !lead.getAssignedEmployee().getId().equals(currentEmployee.getId())) {

            throw new AccessDeniedException("You are not authorized.");
        }

        return followUpRepository.findByLeadId(leadId);
    }

    public List<FollowUp> getFollowUpsByOpportunity(Long opportunityId) {

        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN &&
                !opportunity.getLead().getAssignedEmployee().getId().equals(currentEmployee.getId())) {

            throw new AccessDeniedException("You are not authorized.");
        }

        return followUpRepository.findByOpportunityId(opportunityId);
    }

    public void deleteFollowUp(Long id) {

        FollowUp followUp = getAuthorizedFollowUp(id);

        followUpRepository.delete(followUp);
    }

    private FollowUp getAuthorizedFollowUp(Long id) {

        FollowUp followUp = followUpRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FollowUp not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() == Role.ADMIN) {
            return followUp;
        }

        Employee owner;

        if (followUp.getLead() != null) {
            owner = followUp.getLead().getAssignedEmployee();
        } else {
            owner = followUp.getOpportunity()
                    .getLead()
                    .getAssignedEmployee();
        }

        if (!owner.getId().equals(currentEmployee.getId())) {
            throw new AccessDeniedException(
                    "You are not authorized to access this follow-up.");
        }

        return followUp;
    }
}