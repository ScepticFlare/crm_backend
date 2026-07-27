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
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerService {

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

        if (customerRepository.findByOpportunity(opportunity).isPresent()) {
            throw new ResourceNotFoundException("Customer already exists.");
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
                .website(lead.getWebsite())
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

    public Page<Customer> getAllCustomers(int page, int size, String search) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        Pageable pageable = PageRequest.of(page, size);

        if (search == null) {
            search = "";
        }

        if (currentEmployee.getRole() == Role.ADMIN) {

            return customerRepository.searchCustomers(
                    null,
                    search,
                    pageable
            );

        }

        return customerRepository.searchCustomers(
                currentEmployee,
                search,
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