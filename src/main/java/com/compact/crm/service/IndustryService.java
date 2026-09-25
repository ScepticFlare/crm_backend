package com.compact.crm.service;

import com.compact.crm.dto.request.IndustryRequest;
import com.compact.crm.entity.Industry;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.IndustryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IndustryService {

    private final IndustryRepository repository;

    @CacheEvict(cacheNames = "industries", allEntries = true)
    public Industry create(IndustryRequest request) {

        repository.findByNameIgnoreCase(request.getName())
                .ifPresent(i -> {
                    throw new IllegalArgumentException("Industry already exists");
                });

        Industry industry = Industry.builder()
                .name(request.getName().trim())
                .build();

        return repository.save(industry);
    }

    // Idempotent seed helper - same "findOrCreate" convention as
    // LeadSourceMasterService.findOrCreate/SalesStageService, used by
    // config.PublicLeadIndustrySeeder to guarantee the "Other / Not Listed"
    // row exists at boot without ever duplicating or erroring if it (or an
    // admin-created row with the same name) already exists.
    @CacheEvict(cacheNames = "industries", allEntries = true)
    public Industry findOrCreate(String name) {

        return repository.findByNameIgnoreCase(name)
                .orElseGet(() -> repository.save(
                        Industry.builder().name(name).build()));
    }

    @Cacheable(cacheNames = "industries")
    public List<Industry> getAll() {
        return repository.findByIsActiveTrue();
    }

    public List<Industry> getAllIncludingInactive() {
        return repository.findAll();
    }

    public Industry getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Industry not found"));
    }

    @CacheEvict(cacheNames = "industries", allEntries = true)
    public Industry update(Long id, IndustryRequest request) {

        Industry industry = getById(id);

        industry.setName(request.getName().trim());

        return repository.save(industry);
    }

    @CacheEvict(cacheNames = "industries", allEntries = true)
    public void deactivate(Long id) {

        Industry industry = getById(id);

        industry.setIsActive(false);

        repository.save(industry);
    }

    @CacheEvict(cacheNames = "industries", allEntries = true)
    public void activate(Long id) {

        Industry industry = getById(id);

        industry.setIsActive(true);

        repository.save(industry);
    }

}