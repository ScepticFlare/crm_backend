package com.compact.crm.controller;

import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.service.FollowUpService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import java.util.List;

@RestController
@RequestMapping("/api/followups")
@RequiredArgsConstructor
public class FollowUpController {

    private final FollowUpService followUpService;

    @PostMapping
    public FollowUp createFollowUp(@RequestBody FollowUpRequest request) {
        return followUpService.createFollowUp(request);
    }

    @GetMapping
    public Page<FollowUp> getAllFollowUps(

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "50") int size,

            @RequestParam(defaultValue = "") String search

    ) {

        return followUpService.getAllFollowUps(
                page,
                size,
                search
        );

    }

    @GetMapping("/lead/{leadId}")
    public List<FollowUp> getFollowUpsByLead(
            @PathVariable Long leadId) {

        return followUpService.getFollowUpsByLead(leadId);

    }

    @GetMapping("/opportunity/{opportunityId}")
    public List<FollowUp> getFollowUpsByOpportunity(
            @PathVariable Long opportunityId) {

        return followUpService.getFollowUpsByOpportunity(opportunityId);

    }

    @GetMapping("/{id}")
    public FollowUp getFollowUpById(@PathVariable Long id) {
        return followUpService.getFollowUpById(id);
    }

    @PutMapping("/{id}")
    public FollowUp updateFollowUp(@PathVariable Long id,
                                   @RequestBody FollowUpRequest request) {
        return followUpService.updateFollowUp(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteFollowUp(@PathVariable Long id) {
        followUpService.deleteFollowUp(id);
    }
}