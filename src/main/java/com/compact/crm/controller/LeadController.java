package com.compact.crm.controller;

import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.entity.Lead;
import com.compact.crm.service.LeadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import java.util.List;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;

    @PostMapping
    public Lead createLead(@Valid @RequestBody LeadRequest request) {
        return leadService.createLead(request);
    }

    @GetMapping

    public Page<Lead> getAllLeads(

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search

    ) {

        return leadService.getAllLeads(page, size, search);

    }

    @GetMapping("/{id}")
    public Lead getLeadById(@PathVariable Long id) {
        return leadService.getLeadById(id);
    }

    @PutMapping("/{id}")
    public Lead updateLead(@PathVariable Long id, @Valid @RequestBody LeadRequest request) {
        return leadService.updateLead(id, request);
    }

    @DeleteMapping("/{id}")
    public String deleteLead(@PathVariable Long id) {
        leadService.deleteLead(id);
        return "Lead deleted successfully";
    }
}