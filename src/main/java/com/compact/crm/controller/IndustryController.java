package com.compact.crm.controller;

import com.compact.crm.dto.request.IndustryRequest;
import com.compact.crm.entity.Industry;
import com.compact.crm.service.IndustryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @PutMapping("/{id}")
    public Industry update(
            @PathVariable Long id,
            @Valid @RequestBody IndustryRequest request) {

        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

}