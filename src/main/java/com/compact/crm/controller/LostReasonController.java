package com.compact.crm.controller;

import com.compact.crm.dto.request.LostReasonRequest;
import com.compact.crm.entity.LostReason;
import com.compact.crm.service.LostReasonService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lost-reasons")
@RequiredArgsConstructor
public class LostReasonController {

    private final LostReasonService service;

    @PostMapping
    public LostReason create(
            @Valid @RequestBody LostReasonRequest request) {

        return service.create(request);

    }

    @GetMapping
    public List<LostReason> getAll() {

        return service.getAll();

    }

    @GetMapping("/{id}")
    public LostReason getById(@PathVariable Long id) {

        return service.getById(id);

    }

    @PutMapping("/{id}")
    public LostReason update(
            @PathVariable Long id,
            @Valid @RequestBody LostReasonRequest request) {

        return service.update(id, request);

    }

    // Every sibling master-data controller (Product/Industry/LeadSourceMaster/
    // SalesStage/ActivityType/Battery) already gates its delete endpoint to
    // ADMIN only - this one was missing that annotation, leaving it the one
    // master-data delete endpoint any authenticated Manager/Employee could
    // call directly.
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {

        service.delete(id);

    }

}