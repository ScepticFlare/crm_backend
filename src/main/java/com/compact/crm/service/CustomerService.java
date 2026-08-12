package com.compact.crm.service;

import com.compact.crm.dto.request.CustomerRequest;
import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.Role;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.EmployeeRepository;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomerService {

    // Sentinel "no filter" bounds for the Month filter. Using always-non-null
    // parameters (rather than a nullable param + "(:from IS NULL OR ...)"
    // JPQL pattern) avoids a PostgreSQL/pgjdbc limitation where it cannot
    // determine a bind parameter's data type when that parameter is only
    // ever compared via "? IS NULL" - it needs a concrete typed value.
    private static final LocalDateTime MIN_DATE = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime MAX_DATE = LocalDateTime.of(2100, 1, 1, 0, 0);

    private final CustomerRepository customerRepository;
    private final EmployeeRepository employeeRepository;
    private final OpportunityRepository opportunityRepository;
    private final CurrentUserService currentUserService;

    private String generateCustomerCode() {
        long count = customerRepository.count() + 1;
        return String.format("CUST%04d", count);
    }

    public Customer createCustomer(CustomerRequest request) {

        Employee employee = employeeRepository.findById(request.getAssignedEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        Opportunity opportunity = opportunityRepository.findById(request.getOpportunityId())
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        // Idempotent: an Opportunity can only ever have one Customer.
        Optional<Customer> existing = customerRepository.findByOpportunity(opportunity);

        if (existing.isPresent()) {
            return existing.get();
        }

        Customer customer = Customer.builder()
                .customerCode(generateCustomerCode())
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
                .billingAddress(request.getBillingAddress())
                .shippingAddress(request.getShippingAddress())
                .gstNumber(request.getGstNumber())
                .assignedEmployee(employee)
                .opportunity(opportunity)
                .build();

        return customerRepository.save(customer);
    }

    public Customer convertOpportunity(Long opportunityId, CustomerRequest request) {

        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN &&
                !opportunity.getLead().getAssignedEmployee().getId().equals(currentEmployee.getId())) {
            throw new AccessDeniedException("You are not authorized to convert this opportunity.");
        }

        if (!"WON".equalsIgnoreCase(opportunity.getSalesStage().getName())) {
            throw new ResourceNotFoundException("Only WON opportunities can be converted.");
        }

        // Idempotent: if this Opportunity was already converted, reuse the
        // existing Customer instead of creating a duplicate.
        Optional<Customer> existing = customerRepository.findByOpportunity(opportunity);

        if (existing.isPresent()) {
            return existing.get();
        }

        Lead lead = opportunity.getLead();

        Customer customer = Customer.builder()
                .customerCode(generateCustomerCode())
                .companyName(lead.getCompanyName())
                .contactPerson(lead.getContactPerson())
                .designation(lead.getDesignation())
                .phone(lead.getPhone())
                .alternatePhone(lead.getAlternatePhone())
                .email(lead.getEmail())
                .secondaryEmail(lead.getSecondaryEmail())
                .city(lead.getCity())
                .state(lead.getState())
                .pincode(lead.getPincode())
                .gstNumber(request.getGstNumber())
                .billingAddress(request.getBillingAddress())
                .shippingAddress(request.getShippingAddress())
                .assignedEmployee(lead.getAssignedEmployee())
                .opportunity(opportunity)
                .build();

        return customerRepository.save(customer);
    }

    public Customer getByOpportunityId(Long opportunityId) {

        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN &&
                !opportunity.getLead().getAssignedEmployee().getId().equals(currentEmployee.getId())) {
            throw new AccessDeniedException("You are not authorized to access this opportunity.");
        }

        return customerRepository.findByOpportunity(opportunity)
                .orElseThrow(() -> new ResourceNotFoundException("No customer exists for this opportunity."));
    }

    public Page<Customer> getAllCustomers(int page, int size, String search, Integer year, Integer month) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Pageable pageable = PageRequest.of(page, size);

        if (search == null) {
            search = "";
        }

        LocalDateTime from = MIN_DATE;
        LocalDateTime to = MAX_DATE;

        if (year != null && month != null) {
            from = LocalDate.of(year, month, 1).atStartOfDay();
            to = from.plusMonths(1);
        }

        if (currentEmployee.getRole() == Role.ADMIN) {

            return customerRepository.searchCustomers(
                    null,
                    search,
                    from,
                    to,
                    pageable
            );

        }

        return customerRepository.searchCustomers(
                currentEmployee,
                search,
                from,
                to,
                pageable
        );

    }

    public Customer getCustomerById(Long id) {
        return getAuthorizedCustomer(id);
    }

    public Customer updateCustomer(Long id, CustomerRequest request) {

        Customer customer = getAuthorizedCustomer(id);

        customer.setCompanyName(request.getCompanyName());
        customer.setContactPerson(request.getContactPerson());
        customer.setDesignation(request.getDesignation());
        customer.setPhone(request.getPhone());
        customer.setAlternatePhone(request.getAlternatePhone());
        customer.setEmail(request.getEmail());
        customer.setSecondaryEmail(request.getSecondaryEmail());
        customer.setWebsite(request.getWebsite());
        customer.setCity(request.getCity());
        customer.setState(request.getState());
        customer.setPincode(request.getPincode());
        customer.setBillingAddress(request.getBillingAddress());
        customer.setShippingAddress(request.getShippingAddress());
        customer.setGstNumber(request.getGstNumber());

        if (currentUserService.getCurrentEmployee().getRole() == Role.ADMIN) {

            Employee employee = employeeRepository.findById(request.getAssignedEmployeeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

            Opportunity opportunity = opportunityRepository.findById(request.getOpportunityId())
                    .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

            customer.setAssignedEmployee(employee);
            customer.setOpportunity(opportunity);
        }

        return customerRepository.save(customer);
    }

    public void deleteCustomer(Long id) {

        Customer customer = getAuthorizedCustomer(id);

        customerRepository.delete(customer);
    }

    private Customer getAuthorizedCustomer(Long id) {

        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() == Role.ADMIN) {
            return customer;
        }

        if (!customer.getOpportunity()
                .getLead()
                .getAssignedEmployee()
                .getId()
                .equals(currentEmployee.getId())) {

            throw new AccessDeniedException("You are not authorized to access this customer.");
        }

        return customer;
    }
}