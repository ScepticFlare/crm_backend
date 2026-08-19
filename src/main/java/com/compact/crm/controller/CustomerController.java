package com.compact.crm.controller;

import com.compact.crm.dto.request.BulkIdsRequest;
import com.compact.crm.dto.request.CustomerRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.search.CustomerSearchCriteria;
import com.compact.crm.entity.Customer;
import com.compact.crm.service.CustomerService;
import com.compact.crm.util.CsvWriter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCustomers(
            @ModelAttribute CustomerSearchCriteria criteria,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String ids
    ) {

        List<Long> idList = parseIds(ids);
        List<Customer> customers = customerService.findForExport(criteria, idList, sortBy, sortDir);

        List<String> headers = List.of(
                "Customer Code", "Company", "Contact Person", "Phone", "Email",
                "City", "State", "GST Number", "Assigned Employee", "Created Date"
        );

        List<List<String>> rows = new ArrayList<>();

        for (Customer c : customers) {

            rows.add(List.of(
                    nullToEmpty(c.getCustomerCode()),
                    nullToEmpty(c.getCompanyName()),
                    nullToEmpty(c.getContactPerson()),
                    nullToEmpty(c.getPhone()),
                    nullToEmpty(c.getEmail()),
                    nullToEmpty(c.getCity()),
                    nullToEmpty(c.getState()),
                    nullToEmpty(c.getGstNumber()),
                    c.getAssignedEmployee() != null ? c.getAssignedEmployee().getName() : "",
                    c.getCreatedAt() != null ? c.getCreatedAt().toString() : ""
            ));
        }

        String csv = CsvWriter.write(headers, rows);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.parseMediaType("text/csv"));
        responseHeaders.setContentDisposition(
                ContentDisposition.attachment().filename("customers.csv", StandardCharsets.UTF_8).build());

        return new ResponseEntity<>(body, responseHeaders, HttpStatus.OK);
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

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
