package com.compact.crm.service;

import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.Role;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OpportunityService {

    private final OpportunityRepository opportunityRepository;
    private final LeadRepository leadRepository;
    private final CustomerRepository customerRepository;
    private final CurrentUserService currentUserService;
    private final SalesStageService salesStageService;

    private static final String WON_STAGE_NAME = "WON";

    private static final String WON_LOCK_MESSAGE =
            "This opportunity has already been converted to a customer and cannot be moved out of Won.";

    // Sentinel "no filter" bounds for the Month filter. Using always-non-null
    // parameters (rather than a nullable param + "(:from IS NULL OR ...)"
    // JPQL pattern) avoids a PostgreSQL/pgjdbc limitation where it cannot
    // determine a bind parameter's data type when that parameter is only
    // ever compared via "? IS NULL" - it needs a concrete typed value.
    private static final LocalDateTime MIN_DATE = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime MAX_DATE = LocalDateTime.of(2100, 1, 1, 0, 0);

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
                .leadValidity(request.getLeadValidity())
                .lead(lead)
                .build();

        lead.setLeadValidity(request.getLeadValidity());
        leadRepository.save(lead);

        return opportunityRepository.save(opportunity);
    }

    public Page<Opportunity> getAllOpportunities(
            int page,
            int size,
            String search,
            String stageName,
            Integer year,
            Integer month)
    {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Pageable pageable = PageRequest.of(page, size);

        if (search == null) {
            search = "";
        }

        Employee employeeFilter =
                currentEmployee.getRole() == Role.ADMIN
                        ? null
                        : currentEmployee;

        LocalDateTime[] range = monthRange(year, month);

        return opportunityRepository.searchOpportunitiesByStage(
                employeeFilter,
                stageName,
                search,
                range[0],
                range[1],
                pageable
        );

    }

    public Page<Opportunity> getInProgressOpportunities(
            int page,
            int size,
            String search,
            Integer year,
            Integer month)
    {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Pageable pageable = PageRequest.of(page, size);

        if (search == null) {
            search = "";
        }

        Employee employeeFilter =
                currentEmployee.getRole() == Role.ADMIN
                        ? null
                        : currentEmployee;

        LocalDateTime[] range = monthRange(year, month);

        return opportunityRepository.searchInProgressOpportunities(
                employeeFilter,
                search,
                range[0],
                range[1],
                pageable
        );

    }

    // Returns [from, to) bounds for the given calendar month, or the
    // MIN_DATE/MAX_DATE sentinel range when year/month are not supplied
    // (i.e. no month filter - "All Months").
    private LocalDateTime[] monthRange(Integer year, Integer month) {

        if (year == null || month == null) {
            return new LocalDateTime[]{MIN_DATE, MAX_DATE};
        }

        LocalDateTime from = LocalDate.of(year, month, 1).atStartOfDay();

        return new LocalDateTime[]{from, from.plusMonths(1)};

    }

    public Opportunity getOpportunityById(Long id) {
        return getAuthorizedOpportunity(id);
    }

    public Opportunity updateOpportunity(Long id, OpportunityRequest request) {

        Opportunity opportunity = getAuthorizedOpportunity(id);

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

        return opportunityRepository.save(opportunity);
    }

    public void deleteOpportunity(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id);

        boolean isCurrentlyWon =
                WON_STAGE_NAME.equalsIgnoreCase(opportunity.getSalesStage().getName());

        if (isCurrentlyWon && customerRepository.findByOpportunity(opportunity).isPresent()) {
            throw new IllegalArgumentException(WON_LOCK_MESSAGE);
        }

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