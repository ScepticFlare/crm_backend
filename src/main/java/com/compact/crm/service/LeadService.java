package com.compact.crm.service;

import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.enums.Role;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.ProductRepository;
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
public class LeadService {

    private final CurrentUserService currentUserService;
    private final LeadRepository leadRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductRepository productRepository;
    private final IndustryRepository industryRepository;
    private final LeadSourceMasterRepository leadSourceMasterRepository;

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
                .website(request.getWebsite())
                .city(request.getCity())
                .state(request.getState())
                .pincode(request.getPincode())
                .product(
                        productRepository.findById(request.getProductId())
                                .orElseThrow(() -> new ResourceNotFoundException("Product not found"))
                )
                .industry(
                        industryRepository.findById(request.getIndustryId())
                                .orElseThrow(() -> new ResourceNotFoundException("Industry not found"))
                )
                .description(request.getDescription())
                .leadStatus(request.getLeadStatus())
                .leadValidity(request.getLeadValidity())
                .leadSource(
                        leadSourceMasterRepository.findById(request.getLeadSourceId())
                                .orElseThrow(() -> new ResourceNotFoundException("Lead Source not found"))
                )
                .assignedEmployee(employee)
                .build();
        lead.setFinalRemarks(request.getFinalRemarks());

        return leadRepository.save(lead);
    }

    public Page<Lead> getAllLeads(int page, int size, String search) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Pageable pageable = PageRequest.of(page, size);

        if (search == null) {
            search = "";
        }

        if (currentEmployee.getRole() == Role.ADMIN) {

            return leadRepository.searchLeads(
                    null,
                    search,
                    pageable
            );

        }

        return leadRepository.searchLeads(
                currentEmployee,
                search,
                pageable
        );

    }

    public Lead getLeadById(Long id) {
        return getAuthorizedLead(id);
    }

    public Lead updateLead(Long id, LeadRequest request) {

        Lead lead = getAuthorizedLead(id);

        lead.setCompanyName(request.getCompanyName());
        lead.setContactPerson(request.getContactPerson());
        lead.setDesignation(request.getDesignation());
        lead.setPhone(request.getPhone());
        lead.setAlternatePhone(request.getAlternatePhone());
        lead.setEmail(request.getEmail());
        lead.setSecondaryEmail(request.getSecondaryEmail());
        lead.setWebsite(request.getWebsite());
        lead.setCity(request.getCity());
        lead.setState(request.getState());
        lead.setPincode(request.getPincode());
        lead.setFinalRemarks(request.getFinalRemarks());

        lead.setProduct(
                productRepository.findById(request.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found"))
        );

        lead.setIndustry(
                industryRepository.findById(request.getIndustryId())
                        .orElseThrow(() -> new ResourceNotFoundException("Industry not found"))
        );

        lead.setDescription(request.getDescription());
        lead.setLeadStatus(request.getLeadStatus());
        lead.setLeadValidity(request.getLeadValidity());

        lead.setLeadSource(
                leadSourceMasterRepository.findById(request.getLeadSourceId())
                        .orElseThrow(() -> new ResourceNotFoundException("Lead Source not found"))
        );

        // Only ADMIN can reassign a lead
        if (currentUserService.getCurrentEmployee().getRole() == Role.ADMIN) {

            Employee employee = employeeRepository.findById(request.getAssignedEmployeeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

            lead.setAssignedEmployee(employee);
        }

        return leadRepository.save(lead);
    }

    public void deleteLead(Long id) {

        Lead lead = getAuthorizedLead(id);

        leadRepository.delete(lead);
    }

    private Lead getAuthorizedLead(Long id) {

        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() == Role.ADMIN) {
            return lead;
        }

        if (!lead.getAssignedEmployee().getId().equals(currentEmployee.getId())) {
            throw new AccessDeniedException("You are not authorized to access this lead.");
        }

        return lead;
    }
}