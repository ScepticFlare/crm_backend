package com.compact.crm.service;

import com.compact.crm.dto.request.CustomerRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.search.CustomerSearchCriteria;
import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.specification.CustomerSpecifications;
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

import static com.compact.crm.security.AccessControlService.CUSTOMER_DELETE;
import static com.compact.crm.security.AccessControlService.CUSTOMER_EXPORT;
import static com.compact.crm.security.AccessControlService.CUSTOMER_MANAGE;
import static com.compact.crm.security.AccessControlService.CUSTOMER_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "company", "companyName",
            "contactPerson", "contactPerson",
            "createdDate", "createdAt",
            "assignedEmployee", "assignedEmployee.name"
    );

    private static final int EXPORT_MAX_ROWS = 20_000;

    private final CustomerRepository customerRepository;
    private final EmployeeRepository employeeRepository;
    private final OpportunityRepository opportunityRepository;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;
    private final ActivityLogService activityLogService;

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

        Customer saved = customerRepository.save(customer);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.CUSTOMER, ActivityAction.CREATE,
                saved.getId(), saved.getCompanyName(),
                "Created customer"
        );

        return saved;
    }

    public Customer convertOpportunity(Long opportunityId, CustomerRequest request) {

        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                OPPORTUNITY_MANAGE,
                opportunity.getLead().getAssignedEmployee().getId()
        );

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

        Customer saved = customerRepository.save(customer);

        activityLogService.log(
                currentEmployee,
                ActivityModule.OPPORTUNITY, ActivityAction.CONVERT,
                opportunity.getId(), opportunity.getTitle(),
                "Converted opportunity to customer: " + saved.getCompanyName()
        );

        return saved;
    }

    public Customer getByOpportunityId(Long opportunityId) {

        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                OPPORTUNITY_VIEW,
                opportunity.getLead().getAssignedEmployee().getId()
        );

        return customerRepository.findByOpportunity(opportunity)
                .orElseThrow(() -> new ResourceNotFoundException("No customer exists for this opportunity."));
    }

    public Page<Customer> searchCustomers(
            CustomerSearchCriteria criteria, int page, int size, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, CUSTOMER_VIEW);

        Specification<Customer> spec = buildSpecification(criteria, employeeIds);
        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");

        return customerRepository.findAll(spec, PageRequest.of(page, size, sort));
    }

    public List<Customer> findForExport(
            CustomerSearchCriteria criteria, List<Long> ids, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, CUSTOMER_EXPORT);
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, CUSTOMER_VIEW);

        Specification<Customer> spec = buildSpecification(criteria, employeeIds);

        if (ids != null && !ids.isEmpty()) {
            Specification<Customer> idSpec = CustomerSpecifications.hasIds(ids);
            spec = (spec == null) ? idSpec : spec.and(idSpec);
        }

        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");
        Pageable pageable = PageRequest.of(0, EXPORT_MAX_ROWS, sort);

        List<Customer> customers = customerRepository.findAll(spec, pageable).getContent();

        activityLogService.log(
                currentEmployee,
                ActivityModule.CUSTOMER, ActivityAction.EXPORT,
                null, null,
                "Exported " + customers.size() + " customer(s)"
        );

        return customers;
    }

    public BulkOperationResult bulkDeleteCustomers(List<Long> ids) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, CUSTOMER_DELETE);

        List<Long> succeeded = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();

        for (Long id : ids) {

            Optional<Customer> customerOpt = customerRepository.findById(id);

            if (customerOpt.isEmpty()) {
                skipped.add(id);
                continue;
            }

            Customer customer = customerOpt.get();

            try {

                accessControlService.assertCanAccess(
                        currentEmployee, CUSTOMER_DELETE, customer.getAssignedEmployee().getId());

                customerRepository.delete(customer);
                succeeded.add(id);

                activityLogService.log(
                        currentEmployee,
                        ActivityModule.CUSTOMER, ActivityAction.DELETE,
                        customer.getId(), customer.getCompanyName(),
                        "Deleted customer"
                );

            } catch (AccessDeniedException e) {
                skipped.add(id);
            }
        }

        return new BulkOperationResult(succeeded, skipped);
    }

    private Specification<Customer> buildSpecification(CustomerSearchCriteria criteria, List<Long> employeeIds) {

        LocalDateTime createdFrom = criteria.getCreatedFrom() != null
                ? criteria.getCreatedFrom().atStartOfDay() : null;

        LocalDateTime createdTo = criteria.getCreatedTo() != null
                ? criteria.getCreatedTo().plusDays(1).atStartOfDay() : null;

        return SpecificationUtil.and(java.util.Arrays.asList(
                CustomerSpecifications.ownerIn(employeeIds),
                CustomerSpecifications.search(criteria.getSearch()),
                CustomerSpecifications.hasAssignedEmployeeId(criteria.getAssignedEmployeeId()),
                CustomerSpecifications.hasIndustryId(criteria.getIndustryId()),
                CustomerSpecifications.hasCity(criteria.getCity()),
                CustomerSpecifications.hasState(criteria.getState()),
                CustomerSpecifications.createdBetween(createdFrom, createdTo)
        ));
    }

    public Customer getCustomerById(Long id) {

        Customer customer = getAuthorizedCustomer(id, CUSTOMER_VIEW);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.CUSTOMER, ActivityAction.VIEW,
                customer.getId(), customer.getCompanyName(),
                "Viewed customer"
        );

        return customer;
    }

    public Customer updateCustomer(Long id, CustomerRequest request) {

        Customer customer = getAuthorizedCustomer(id, CUSTOMER_MANAGE);

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

        // Reassignment (both the owning employee and which Opportunity this
        // Customer links to) follows the same ALL/TEAM/OWN rule as Lead
        // reassignment: ADMIN may repoint to anyone/anything, MANAGER only
        // within their own team, EMPLOYEE (or no grant) not at all.
        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (accessControlService.canAssignTo(currentEmployee, CUSTOMER_MANAGE, request.getAssignedEmployeeId())) {

            Employee employee = employeeRepository.findById(request.getAssignedEmployeeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

            Opportunity opportunity = opportunityRepository.findById(request.getOpportunityId())
                    .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

            // A Manager may only re-link the customer to an Opportunity
            // that's also within their team - otherwise this would be a
            // side-channel to view/borrow another team's Opportunity.
            accessControlService.assertCanAccess(
                    currentEmployee,
                    OPPORTUNITY_MANAGE,
                    opportunity.getLead().getAssignedEmployee().getId()
            );

            customer.setAssignedEmployee(employee);
            customer.setOpportunity(opportunity);
        }

        Customer saved = customerRepository.save(customer);

        activityLogService.log(
                currentEmployee,
                ActivityModule.CUSTOMER, ActivityAction.UPDATE,
                saved.getId(), saved.getCompanyName(),
                "Updated customer"
        );

        return saved;
    }

    public void deleteCustomer(Long id) {

        Customer customer = getAuthorizedCustomer(id, CUSTOMER_DELETE);

        customerRepository.delete(customer);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.CUSTOMER, ActivityAction.DELETE,
                customer.getId(), customer.getCompanyName(),
                "Deleted customer"
        );
    }

    // Backs GET /api/customers/{id}/delete-impact. Nothing in this schema
    // references customers.id as a foreign key (a Customer is the terminal
    // node of the Lead -> Opportunity -> Customer chain, not a parent of
    // anything else - FollowUps attach to a Lead or an Opportunity, never a
    // Customer), so a Customer delete never has dependents to report. Kept
    // as a real endpoint anyway (rather than skipping the preview call on
    // the frontend for this one record type) so the Admin-facing delete
    // flow is identical across Leads/Opportunities/Customers.
    public DeleteImpactResponse getDeleteImpact(Long id) {

        getAuthorizedCustomer(id, CUSTOMER_DELETE);

        return new DeleteImpactResponse(0, 0, 0, false);
    }

    private Customer getAuthorizedCustomer(Long id, String permissionCode) {

        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        // Ownership is the Customer's own assignedEmployee - the same field
        // the list query (searchCustomers) filters by, so list and
        // single-record access always agree on one source of truth.
        accessControlService.assertCanAccess(
                currentEmployee,
                permissionCode,
                customer.getAssignedEmployee().getId()
        );

        return customer;
    }
}