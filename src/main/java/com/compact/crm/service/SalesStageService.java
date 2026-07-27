package com.compact.crm.service;

import com.compact.crm.dto.request.SalesStageRequest;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.SalesStageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SalesStageService {

    private final SalesStageRepository repository;

    public SalesStage create(SalesStageRequest request) {

        String stageName = request.getName().trim();

        repository.findByNameIgnoreCase(stageName)
                .ifPresent(stage -> {
                    throw new IllegalArgumentException("Sales stage already exists");
                });

        SalesStage salesStage = SalesStage.builder()
                .name(stageName)
                .build();

        return repository.save(salesStage);
    }

    /**
     * Returns an existing Sales Stage if found.
     * Otherwise creates a new one automatically.
     */
    public SalesStage findOrCreate(String name) {

        String stageName = name.trim();

        return repository.findByNameIgnoreCase(stageName)
                .orElseGet(() -> repository.save(
                        SalesStage.builder()
                                .name(stageName)
                                .build()
                ));
    }
    public SalesStage getByName(String name) {

        return repository.findByNameIgnoreCase(name.trim())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Sales stage not found"));

    }

    public List<SalesStage> getAll() {
        return repository.findByIsActiveTrue();
    }

    public SalesStage getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Sales stage not found"));
    }

    public SalesStage update(Long id, SalesStageRequest request) {

        SalesStage salesStage = getById(id);

        salesStage.setName(request.getName().trim());

        return repository.save(salesStage);
    }

    public void delete(Long id) {

        SalesStage salesStage = getById(id);

        repository.delete(salesStage);
    }

}