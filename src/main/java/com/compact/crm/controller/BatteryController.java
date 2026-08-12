package com.compact.crm.controller;

import com.compact.crm.dto.request.BatteryRequest;
import com.compact.crm.entity.Battery;
import com.compact.crm.service.BatteryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/batteries")
@RequiredArgsConstructor
public class BatteryController {

    private final BatteryService service;

    @PostMapping
    public Battery create(
            @Valid @RequestBody BatteryRequest request) {

        return service.create(request);

    }

    @GetMapping
    public List<Battery> getAll() {

        return service.getAll();

    }

    @GetMapping("/{id}")
    public Battery getById(@PathVariable Long id) {

        return service.getById(id);

    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Battery> getAllIncludingInactive() {

        return service.getAllIncludingInactive();

    }

    @PutMapping("/{id}")
    public Battery update(
            @PathVariable Long id,
            @Valid @RequestBody BatteryRequest request) {

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
