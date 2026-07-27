package com.compact.crm.service;

import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.Role;
import com.compact.crm.exception.ResourceNotFoundException;
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
public class OpportunityService {

    private final OpportunityRepository opportunityRepository;
    private final LeadRepository leadRepository;
    private final CurrentUserService currentUserService;
    private final SalesStageService salesStageService;

    /**
     * Convert a Lead into an Opportunity.
     */
    public Opportunity convertLeadToOpportunity(Long leadId, OpportunityRequest request) {

        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        // Admin can convert any lead
        if (currentEmployee.getRole() != Role.ADMIN &&
                !lead.getAssignedEmployee().getId().equals(currentEmployee.getId())) {
            throw new AccessDeniedException("You are not authorized to convert this lead.");
        }

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
                .lead(lead)
                .build();

        return opportunityRepository.save(opportunity);
    }

    public Page<Opportunity> getAllOpportunities(int page, int size, String search) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Pageable pageable = PageRequest.of(page, size);

        if (search == null) {
            search = "";
        }

        if (currentEmployee.getRole() == Role.ADMIN) {

            return opportunityRepository.searchOpportunities(
                    null,
                    search,
                    pageable
            );

        }

        return opportunityRepository.searchOpportunities(
                currentEmployee,
                search,
                pageable
        );

    }

    public Opportunity getOpportunityById(Long id) {
        return getAuthorizedOpportunity(id);
    }

    public Opportunity updateOpportunity(Long id, OpportunityRequest request) {

        Opportunity opportunity = getAuthorizedOpportunity(id);

        SalesStage salesStage = salesStageService.findOrCreate(request.getSalesStage());

        opportunity.setTitle(request.getTitle());
        opportunity.setProductValue(request.getProductValue());
        opportunity.setExpectedClosingDate(request.getExpectedClosingDate());
        opportunity.setSalesStage(salesStage);

        return opportunityRepository.save(opportunity);
    }

    public void deleteOpportunity(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id);

        opportunityRepository.delete(opportunity);
    }

    private Opportunity getAuthorizedOpportunity(Long id) {

        Opportunity opportunity = opportunityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() == Role.ADMIN) {
            return opportunity;
        }

        if (!opportunity.getLead().getAssignedEmployee().getId().equals(currentEmployee.getId())) {
            throw new AccessDeniedException("You are not authorized to access this opportunity.");
        }

        return opportunity;
    }
}