package com.compact.crm.controller;

import com.compact.crm.dto.request.LeadSourceMasterRequest;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.service.LeadSourceMasterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lead-sources")
@RequiredArgsConstructor
public class LeadSourceMasterController {

    private final LeadSourceMasterService service;

    @PostMapping
    public LeadSourceMaster create(@Valid @RequestBody LeadSourceMasterRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<LeadSourceMaster> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public LeadSourceMaster getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PutMapping("/{id}")
    public LeadSourceMaster update(
            @PathVariable Long id,
            @Valid @RequestBody LeadSourceMasterRequest request) {

        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}