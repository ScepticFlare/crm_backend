package com.compact.crm.service;

import com.compact.crm.dto.request.ActivityTypeRequest;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.ActivityTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityTypeService {

    private final ActivityTypeRepository repository;

    public ActivityType create(ActivityTypeRequest request) {

        repository.findByNameIgnoreCase(request.getName())
                .ifPresent(a -> {
                    throw new IllegalArgumentException("Activity already exists");
                });

        ActivityType activity = ActivityType.builder()
                .name(request.getName().trim())
                .build();

        return repository.save(activity);
    }

    public List<ActivityType> getAll() {
        return repository.findAll();
    }

    public ActivityType getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Activity not found"));
    }

    public ActivityType update(Long id, ActivityTypeRequest request) {

        ActivityType activity = getById(id);

        activity.setName(request.getName().trim());

        return repository.save(activity);
    }

    public void delete(Long id) {

        ActivityType activity = getById(id);

        repository.delete(activity);
    }
}