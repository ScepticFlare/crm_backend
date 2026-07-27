package com.compact.crm.controller;

import com.compact.crm.dto.request.SalesStageRequest;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.service.SalesStageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sales-stages")
@RequiredArgsConstructor
public class SalesStageController {

    private final SalesStageService service;

    @PostMapping
    public SalesStage create(
            @Valid @RequestBody SalesStageRequest request) {

        return service.create(request);
    }

    @GetMapping
    public List<SalesStage> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public SalesStage getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PutMapping("/{id}")
    public SalesStage update(
            @PathVariable Long id,
            @Valid @RequestBody SalesStageRequest request) {

        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}