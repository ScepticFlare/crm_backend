package com.compact.crm.service;

import com.compact.crm.dto.request.LeadBatteryRequest;
import com.compact.crm.dto.request.LeadProductRequest;
import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Product;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Role;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.BatteryRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeadService {

    // Sentinel "no filter" bounds for the Month filter. Using always-non-null
    // parameters (rather than a nullable param + "(:from IS NULL OR ...)"
    // JPQL pattern) avoids a PostgreSQL/pgjdbc limitation where it cannot
    // determine a bind parameter's data type when that parameter is only
    // ever compared via "? IS NULL" - it needs a concrete typed value.
    private static final LocalDateTime MIN_DATE = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime MAX_DATE = LocalDateTime.of(2100, 1, 1, 0, 0);

    private final CurrentUserService currentUserService;
    private final LeadRepository leadRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductRepository productRepository;
    private final IndustryRepository industryRepository;
    private final LeadSourceMasterRepository leadSourceMasterRepository;
    private final BatteryRepository batteryRepository;

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

        return leadRepository.save(lead);
    }

    public Page<Lead> getAllLeads(int page, int size, String search, Integer year, Integer month) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Pageable pageable = PageRequest.of(page, size);

        if (search == null) {
            search = "";
        }

        LocalDateTime[] range = monthRange(year, month);

        if (currentEmployee.getRole() == Role.ADMIN) {

            return leadRepository.searchLeads(
                    null,
                    search,
                    range[0],
                    range[1],
                    pageable
            );

        }

        return leadRepository.searchLeads(
                currentEmployee,
                search,
                range[0],
                range[1],
                pageable
        );

    }

    public Page<Lead> getLeadsByStatus(int page, int size, String search, LeadStatus status, Integer year, Integer month) {

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

        return leadRepository.searchLeadsByStatus(
                employeeFilter,
                status,
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

        // Only ADMIN can reassign a lead
        if (currentUserService.getCurrentEmployee().getRole() == Role.ADMIN) {

            Employee employee = employeeRepository.findById(request.getAssignedEmployeeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

            lead.setAssignedEmployee(employee);
        }

        applyBatteries(lead, request.getBatteries());
        applyProducts(lead, request.getProducts());

        return leadRepository.save(lead);
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