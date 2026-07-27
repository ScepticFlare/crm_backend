package com.compact.crm.service;

import com.compact.crm.dto.request.LostReasonRequest;
import com.compact.crm.entity.LostReason;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.LostReasonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LostReasonService {

    private final LostReasonRepository repository;

    public LostReason create(LostReasonRequest request) {

        repository.findByNameIgnoreCase(request.getName())
                .ifPresent(reason -> {
                    throw new IllegalArgumentException("Lost reason already exists");
                });

        LostReason lostReason = LostReason.builder()
                .name(request.getName().trim())
                .build();

        return repository.save(lostReason);
    }

    public List<LostReason> getAll() {
        return repository.findAll();
    }

    public LostReason getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Lost reason not found"));
    }

    public LostReason update(Long id, LostReasonRequest request) {

        LostReason lostReason = getById(id);

        lostReason.setName(request.getName().trim());

        return repository.save(lostReason);
    }

    public void delete(Long id) {

        repository.delete(getById(id));

    }

}