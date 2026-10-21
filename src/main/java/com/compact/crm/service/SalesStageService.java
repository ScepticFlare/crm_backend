package com.compact.crm.service;

import com.compact.crm.dto.request.SalesStageRequest;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.SalesStageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SalesStageService {

    private static final Logger log = LoggerFactory.getLogger(SalesStageService.class);

    private final SalesStageRepository repository;

    public SalesStage create(SalesStageRequest request) {

        String stageName = request.getName().trim();

        if (stageName.isEmpty()) {
            throw new IllegalArgumentException("Sales stage name is required");
        }

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

    // The (single) stage carrying a core type, whatever its display name is
    // today. Used by workflow actions that must land on a specific core stage
    // (e.g. "Return to In Progress").
    public SalesStage getByType(SalesStageType type) {

        List<SalesStage> matches = repository.findByStageType(type);

        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "The " + type.getDefaultName() + " stage is not configured. Ask an Admin to check the sales stages.");
        }

        return matches.get(0);
    }

    public List<SalesStage> getAll() {
        return repository.findByIsActiveTrue();
    }

    public List<SalesStage> getAllIncludingInactive() {
        return repository.findAll();
    }

    public SalesStage getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Sales stage not found"));
    }

    // Any stage - including the core ones - may be renamed. Only the display
    // name changes: stageType (what the stage means to the workflow) and the
    // id (what Opportunities point at) are untouched.
    public SalesStage update(Long id, SalesStageRequest request) {

        SalesStage salesStage = getById(id);

        String newName = request.getName().trim();

        if (newName.isEmpty()) {
            throw new IllegalArgumentException("Sales stage name is required");
        }

        repository.findByNameIgnoreCase(newName)
                .filter(other -> !other.getId().equals(salesStage.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("Sales stage already exists");
                });

        salesStage.setName(newName);

        return repository.save(salesStage);
    }

    // Soft deactivation: Opportunities keep pointing at the stage (nothing is
    // rewritten) and it simply stops being offered for new selections. Core
    // workflow stages cannot be deactivated - the workflow depends on them -
    // but they can be renamed.
    public void deactivate(Long id) {

        SalesStage salesStage = getById(id);

        SalesStageType type = salesStage.getStageType();

        if (type != null && type.isCore()) {
            throw new IllegalArgumentException(
                    "\"" + salesStage.getName() + "\" is a core workflow stage and cannot be deactivated. "
                            + "You can rename it instead.");
        }

        salesStage.setIsActive(false);

        repository.save(salesStage);
    }

    public void activate(Long id) {

        SalesStage salesStage = getById(id);

        salesStage.setIsActive(true);

        repository.save(salesStage);
    }

    // Makes sure every core workflow stage exists and carries its stable
    // type, without ever guessing and without rewriting any Opportunity.
    // Idempotent; run at startup (config.SalesStageSeeder).
    //
    // For a type no stage carries yet:
    //  - exactly one untyped stage whose name equals the type's default name
    //    (case/whitespace-insensitive) is tagged with it;
    //  - none -> a seeded type's stage is created (the QUOTATION SENT / NEGOTIATION legacy types are not);
    //  - several -> ambiguous: nothing is tagged or created, and an error is
    //    logged so an Admin can resolve it (never a silent guess).
    // Renamed stages are found by their type, so they are never re-created.
    public void ensureCoreStages() {

        for (SalesStageType type : SalesStageType.values()) {

            if (!repository.findByStageType(type).isEmpty()) {
                continue;
            }

            List<SalesStage> candidates = repository.findByStageTypeIsNull().stream()
                    .filter(s -> OpportunityWorkflow.normalize(s.getName()).equals(type.getDefaultName()))
                    .toList();

            if (candidates.size() == 1) {

                SalesStage stage = candidates.get(0);
                stage.setStageType(type);
                repository.save(stage);

                log.info("Sales stage \"{}\" (id {}) tagged as core stage {}", stage.getName(), stage.getId(), type);

            } else if (candidates.size() > 1) {

                log.error("Cannot tag a sales stage as {}: {} stages match the name \"{}\" - resolve manually.",
                        type, candidates.size(), type.getDefaultName());

            } else if (type.isSeeded()) {

                repository.save(SalesStage.builder()
                        .name(type.getDefaultName())
                        .stageType(type)
                        .build());

                log.info("Created missing sales stage {}", type);
            }
        }
    }
}
