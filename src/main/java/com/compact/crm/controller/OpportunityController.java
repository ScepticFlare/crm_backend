package com.compact.crm.controller;

import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.service.OpportunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import java.util.List;

@RestController
@RequestMapping("/api/opportunities")
@RequiredArgsConstructor
public class OpportunityController {

    private final OpportunityService opportunityService;

    @PostMapping("/convert/{leadId}")
    public Opportunity convertLeadToOpportunity(
            @PathVariable Long leadId,
            @RequestBody OpportunityRequest request) {

        return opportunityService.convertLeadToOpportunity(leadId, request);
    }

    @GetMapping

    public Page<Opportunity> getAllOpportunities(

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search

    ) {

        return opportunityService.getAllOpportunities(
                page,
                size,
                search
        );

    }

    @GetMapping("/{id}")
    public Opportunity getOpportunityById(@PathVariable Long id) {
        return opportunityService.getOpportunityById(id);
    }

    @PutMapping("/{id}")
    public Opportunity updateOpportunity(
            @PathVariable Long id,
            @RequestBody OpportunityRequest request) {

        return opportunityService.updateOpportunity(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteOpportunity(@PathVariable Long id) {
        opportunityService.deleteOpportunity(id);
    }
}