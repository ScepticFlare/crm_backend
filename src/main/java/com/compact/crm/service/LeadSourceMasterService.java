package com.compact.crm.service;

import com.compact.crm.dto.request.LeadSourceMasterRequest;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.LeadSourceMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LeadSourceMasterService {

    private final LeadSourceMasterRepository repository;

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

    public List<LeadSourceMaster> getAll() {
        return repository.findAll();
    }

    public LeadSourceMaster getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Lead source not found"));
    }

    public LeadSourceMaster update(Long id, LeadSourceMasterRequest request) {

        LeadSourceMaster source = getById(id);

        source.setName(request.getName().trim());

        return repository.save(source);
    }

    public void delete(Long id) {
        repository.delete(getById(id));
    }

}