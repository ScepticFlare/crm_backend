package com.compact.crm.controller;

import com.compact.crm.dto.request.ActivityTypeRequest;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.service.ActivityTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/activity-types")
@RequiredArgsConstructor
public class ActivityTypeController {

    private final ActivityTypeService service;

    @PostMapping
    public ActivityType create(
            @Valid @RequestBody ActivityTypeRequest request) {

        return service.create(request);
    }

    @GetMapping
    public List<ActivityType> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public ActivityType getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ActivityType> getAllIncludingInactive() {
        return service.getAllIncludingInactive();
    }

    @PutMapping("/{id}")
    public ActivityType update(
            @PathVariable Long id,
            @Valid @RequestBody ActivityTypeRequest request) {

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