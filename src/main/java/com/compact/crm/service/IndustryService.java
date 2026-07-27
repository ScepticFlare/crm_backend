package com.compact.crm.service;

import com.compact.crm.dto.request.IndustryRequest;
import com.compact.crm.entity.Industry;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.IndustryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IndustryService {

    private final IndustryRepository repository;

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

    public List<Industry> getAll() {
        return repository.findAll();
    }

    public Industry getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Industry not found"));
    }

    public Industry update(Long id, IndustryRequest request) {

        Industry industry = getById(id);

        industry.setName(request.getName().trim());

        return repository.save(industry);
    }

    public void delete(Long id) {

        Industry industry = getById(id);

        repository.delete(industry);
    }

}