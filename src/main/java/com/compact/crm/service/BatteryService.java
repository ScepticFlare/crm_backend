package com.compact.crm.service;

import com.compact.crm.dto.request.BatteryRequest;
import com.compact.crm.entity.Battery;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.BatteryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BatteryService {

    private final BatteryRepository repository;

    public Battery create(BatteryRequest request) {

        repository.findByNameIgnoreCase(request.getName())
                .ifPresent(battery -> {
                    throw new IllegalArgumentException("Battery already exists");
                });

        Battery battery = Battery.builder()
                .name(request.getName().trim())
                .build();

        return repository.save(battery);

    }

    public List<Battery> getAll() {
        return repository.findByIsActiveTrue();
    }

    public List<Battery> getAllIncludingInactive() {
        return repository.findAll();
    }

    public Battery getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Battery not found"));

    }

    public Battery update(Long id, BatteryRequest request) {

        Battery battery = getById(id);

        battery.setName(request.getName().trim());

        return repository.save(battery);

    }

    public void deactivate(Long id) {

        Battery battery = getById(id);

        battery.setIsActive(false);

        repository.save(battery);

    }

    public void activate(Long id) {

        Battery battery = getById(id);

        battery.setIsActive(true);

        repository.save(battery);

    }

}
