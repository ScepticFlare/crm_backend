package com.compact.crm.service;

import com.compact.crm.dto.request.LeadSourceMasterRequest;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.LeadSourceMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LeadSourceMasterService {

    private final LeadSourceMasterRepository repository;

    @CacheEvict(cacheNames = "leadSources", allEntries = true)
    public LeadSourceMaster create(LeadSourceMasterRequest request) {

        repository.findByNameIgnoreCase(request.getName())
                .ifPresent(ls -> {
                    throw new IllegalArgumentException("Lead source already exists");
                });

        LeadSourceMaster source = LeadSourceMaster.builder()
                .name(request.getName().trim())
                .build();

        return repository.save(source);
    }

    // Idempotent seed helper - same "findOrCreate" convention as
    // SalesStageService, used by config.PublicLeadSourceSeeder to guarantee
    // the "Website Form" / "Brochure QR" rows exist at boot without ever
    // duplicating or erroring if an admin already created them by hand.
    @CacheEvict(cacheNames = "leadSources", allEntries = true)
    public LeadSourceMaster findOrCreate(String name) {

        return repository.findByNameIgnoreCase(name)
                .orElseGet(() -> repository.save(
                        LeadSourceMaster.builder().name(name).build()));
    }

    @Cacheable(cacheNames = "leadSources")
    public List<LeadSourceMaster> getAll() {
        return repository.findByIsActiveTrue();
    }

    public List<LeadSourceMaster> getAllIncludingInactive() {
        return repository.findAll();
    }

    public LeadSourceMaster getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Lead source not found"));
    }

    @CacheEvict(cacheNames = "leadSources", allEntries = true)
    public LeadSourceMaster update(Long id, LeadSourceMasterRequest request) {

        LeadSourceMaster source = getById(id);

        source.setName(request.getName().trim());

        return repository.save(source);
    }

    @CacheEvict(cacheNames = "leadSources", allEntries = true)
    public void deactivate(Long id) {

        LeadSourceMaster source = getById(id);

        source.setIsActive(false);

        repository.save(source);
    }

    @CacheEvict(cacheNames = "leadSources", allEntries = true)
    public void activate(Long id) {

        LeadSourceMaster source = getById(id);

        source.setIsActive(true);

        repository.save(source);
    }

}
