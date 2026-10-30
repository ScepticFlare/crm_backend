package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.CustomerRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.search.CustomerSearchCriteria;
import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Lead;
import com.compact.crm.service.CustomerService;
import com.compact.crm.service.OpportunityService;
import com.compact.crm.util.TableExport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping
    public Customer createCustomer(@RequestBody CustomerRequest request) {
        return customerService.createCustomer(request);
    }

    @PostMapping("/convert/{opportunityId}")
    public Customer convertOpportunity(
            @PathVariable Long opportunityId,
            @RequestBody CustomerRequest request) {

        return customerService.convertOpportunity(opportunityId, request);
    }

    @GetMapping
    public Page<Customer> getAllCustomers(
            @ModelAttribute CustomerSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        return customerService.searchCustomers(criteria, page, size, sortBy, sortDir);
    }

    @GetMapping("/{id}")
    public Customer getCustomerById(@PathVariable Long id) {
        return customerService.getCustomerById(id);
    }

    @GetMapping("/by-opportunity/{opportunityId}")
    public Customer getByOpportunityId(@PathVariable Long opportunityId) {
        return customerService.getByOpportunityId(opportunityId);
    }

    @PutMapping("/{id}")
    public Customer updateCustomer(@PathVariable Long id,
                                   @RequestBody CustomerRequest request) {
        return customerService.updateCustomer(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteCustomer(@PathVariable Long id) {
        customerService.deleteCustomer(id);
    }

    // Preview for the delete confirmation dialog. A Customer is the
    // terminal node of the Lead -> Opportunity -> Customer chain (nothing
    // references it), so this always reports zero dependents - kept as a
    // real endpoint so the frontend delete flow is identical across
    // Leads/Opportunities/Customers. Same CUSTOMER_DELETE authorization as
    // the delete itself.
    @GetMapping("/{id}/delete-impact")
    public DeleteImpactResponse getCustomerDeleteImpact(@PathVariable Long id) {
        return customerService.getDeleteImpact(id);
    }

    @PostMapping("/bulk-delete")
    public BulkOperationResult bulkDeleteCustomers(@Valid @RequestBody BulkIdsRequest request) {
        return customerService.bulkDeleteCustomers(request.getIds());
    }

    // Every column a Customer export can contain, keyed by the same keys as
    // the Customers list's column chooser (pages/Customers.jsx). Products /
    // batteries / value / title come from the won Opportunity.
    private static final Map<String, TableExport.Column<Customer>> EXPORT_COLUMNS = exportColumns();

    // Used when the request names no (known) columns - an older client.
    private static final List<String> DEFAULT_EXPORT_COLUMNS = List.of(
            "customerCode", "companyName", "contactPerson", "phone", "email", "city", "state",
            "gstNumber", "assignedEmployee", "createdAt");

    private static Map<String, TableExport.Column<Customer>> exportColumns() {

        Map<String, TableExport.Column<Customer>> c = new LinkedHashMap<>();

        c.put("customerCode", TableExport.Column.text("Customer Code", Customer::getCustomerCode));
        c.put("companyName", TableExport.Column.text("Company", Customer::getCompanyName));
        c.put("contactPerson", TableExport.Column.text("Contact Person", Customer::getContactPerson));
        c.put("designation", TableExport.Column.text("Designation", Customer::getDesignation));
        c.put("phone", TableExport.Column.text("Phone", Customer::getPhone));
        c.put("alternatePhone", TableExport.Column.text("Alternate Phone", Customer::getAlternatePhone));
        c.put("email", TableExport.Column.text("Email", Customer::getEmail));
        c.put("secondaryEmail", TableExport.Column.text("Secondary Email", Customer::getSecondaryEmail));
        c.put("website", TableExport.Column.text("Website", Customer::getWebsite));
        c.put("city", TableExport.Column.text("City", Customer::getCity));
        c.put("state", TableExport.Column.text("State", Customer::getState));
        c.put("pincode", TableExport.Column.text("Pincode", Customer::getPincode));
        c.put("billingAddress", TableExport.Column.text("Billing Address", Customer::getBillingAddress));
        c.put("shippingAddress", TableExport.Column.text("Shipping Address", Customer::getShippingAddress));
        c.put("gstNumber", TableExport.Column.text("GST Number", Customer::getGstNumber));
        c.put("industry", TableExport.Column.text("Industry", cu -> {
            Lead lead = cu.getOpportunity() != null ? cu.getOpportunity().getLead() : null;
            return lead != null && lead.getIndustry() != null ? lead.getIndustry().getName() : null;
        }));
        c.put("opportunity", TableExport.Column.text("Opportunity",
                cu -> cu.getOpportunity() != null ? cu.getOpportunity().getTitle() : null));
        c.put("value", TableExport.Column.decimal("Value",
                cu -> cu.getOpportunity() != null ? cu.getOpportunity().getProductValue() : null));
        c.put("products", TableExport.Column.text("Products",
                cu -> cu.getOpportunity() != null ? OpportunityService.describeProducts(cu.getOpportunity()) : null));
        c.put("batteries", TableExport.Column.text("Batteries",
                cu -> cu.getOpportunity() != null ? OpportunityService.describeBatteries(cu.getOpportunity()) : null));
        c.put("assignedEmployee", TableExport.Column.text("Assigned Employee",
                cu -> cu.getAssignedEmployee() != null ? cu.getAssignedEmployee().getName() : null));
        c.put("createdAt", TableExport.Column.dateTime("Created Date", Customer::getCreatedAt));
        c.put("updatedAt", TableExport.Column.dateTime("Last Modified", Customer::getUpdatedAt));

        return c;
    }

    // `columns`: comma-separated keys of EXPORT_COLUMNS, in order. `ids`:
    // exactly those rows; otherwise every matching row minus `excludeIds`.
    // Always within the caller's scope and the active filters.
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCustomers(
            @ModelAttribute CustomerSearchCriteria criteria,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) String excludeIds,
            @RequestParam(required = false) String columns,
            @RequestParam(required = false) String format
    ) {

        List<Customer> customers = customerService.findForExport(
                criteria, parseIds(ids), parseIds(excludeIds), sortBy, sortDir);

        return TableExport.response("customers", format, "Customers",
                TableExport.select(EXPORT_COLUMNS, columns, DEFAULT_EXPORT_COLUMNS), customers);
    }

    private List<Long> parseIds(String ids) {

        if (ids == null || ids.isBlank()) {
            return null;
        }

        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

}
