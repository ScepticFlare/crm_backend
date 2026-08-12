package com.compact.crm.controller;

import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.service.OpportunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

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

            @RequestParam(defaultValue = "") String search,

            @RequestParam(required = false) Integer year,

            @RequestParam(required = false) Integer month

    ) {

        return opportunityService.getAllOpportunities(
                page,
                size,
                search,
                null,
                year,
                month
        );

    }

    @GetMapping("/in-progress")
    public Page<Opportunity> getInProgressOpportunities(

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search,

            @RequestParam(required = false) Integer year,

            @RequestParam(required = false) Integer month

    ) {

        return opportunityService.getInProgressOpportunities(
                page,
                size,
                search,
                year,
                month
        );

    }

    @GetMapping("/postponed")
    public Page<Opportunity> getPostponedOpportunities(

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search,

            @RequestParam(required = false) Integer year,

            @RequestParam(required = false) Integer month

    ) {

        return opportunityService.getAllOpportunities(
                page,
                size,
                search,
                "POSTPONED",
                year,
                month
        );

    }

    @GetMapping("/dropped")
    public Page<Opportunity> getDroppedOpportunities(

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search,

            @RequestParam(required = false) Integer year,

            @RequestParam(required = false) Integer month

    ) {

        return opportunityService.getAllOpportunities(
                page,
                size,
                search,
                "DROPPED",
                year,
                month
        );

    }

    @GetMapping("/lost")
    public Page<Opportunity> getLostOpportunities(

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search,

            @RequestParam(required = false) Integer year,

            @RequestParam(required = false) Integer month

    ) {

        return opportunityService.getAllOpportunities(
                page,
                size,
                search,
                "LOST",
                year,
                month
        );

    }

    @GetMapping("/unresponsive")
    public Page<Opportunity> getUnresponsiveOpportunities(

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search,

            @RequestParam(required = false) Integer year,

            @RequestParam(required = false) Integer month

    ) {

        return opportunityService.getAllOpportunities(
                page,
                size,
                search,
                "UNRESPONSIVE",
                year,
                month
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