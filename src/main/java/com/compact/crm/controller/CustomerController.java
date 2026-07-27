package com.compact.crm.controller;

import com.compact.crm.dto.request.CustomerRequest;
import com.compact.crm.entity.Customer;
import com.compact.crm.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
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

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search

    ) {

        return customerService.getAllCustomers(
                page,
                size,
                search
        );

    }
    @GetMapping("/{id}")
    public Customer getCustomerById(@PathVariable Long id) {
        return customerService.getCustomerById(id);
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
}