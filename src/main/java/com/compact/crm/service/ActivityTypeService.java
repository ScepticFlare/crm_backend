package com.compact.crm.service;

import com.compact.crm.dto.request.ActivityTypeRequest;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.ActivityTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityTypeService {

    private final ActivityTypeRepository repository;

    @CacheEvict(cacheNames = "activityTypes", allEntries = true)
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

    @Cacheable(cacheNames = "activityTypes")
    public List<ActivityType> getAll() {
        return repository.findByIsActiveTrue();
    }

    public List<ActivityType> getAllIncludingInactive() {
        return repository.findAll();
    }

    public ActivityType getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Activity not found"));
    }

    @CacheEvict(cacheNames = "activityTypes", allEntries = true)
    public ActivityType update(Long id, ActivityTypeRequest request) {

        ActivityType activity = getById(id);

        activity.setName(request.getName().trim());

        return repository.save(activity);
    }

    @CacheEvict(cacheNames = "activityTypes", allEntries = true)
    public void deactivate(Long id) {

        ActivityType activity = getById(id);

        activity.setIsActive(false);

        repository.save(activity);
    }

    @CacheEvict(cacheNames = "activityTypes", allEntries = true)
    public void activate(Long id) {

        ActivityType activity = getById(id);

        activity.setIsActive(true);

        repository.save(activity);
    }
}
