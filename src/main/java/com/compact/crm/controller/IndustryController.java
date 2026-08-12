package com.compact.crm.controller;

import com.compact.crm.dto.request.IndustryRequest;
import com.compact.crm.entity.Industry;
import com.compact.crm.service.IndustryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/industries")
@RequiredArgsConstructor
public class IndustryController {

    private final IndustryService service;

    @PostMapping
    public Industry create(
            @Valid @RequestBody IndustryRequest request) {

        return service.create(request);
    }

    @GetMapping
    public List<Industry> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public Industry getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Industry> getAllIncludingInactive() {
        return service.getAllIncludingInactive();
    }

    @PutMapping("/{id}")
    public Industry update(
            @PathVariable Long id,
            @Valid @RequestBody IndustryRequest request) {

        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void deactivate(@PathVariable Long id) {
        service.deactivate(id);
    }

    @PutMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public void activate(@PathVariable Long id) {
        service.activate(id);
    }

}