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

    // Custom stages are withdrawn: the workflow has exactly five main stages
    // (SalesStageType.isMainStage), created and kept by ensureCoreStages.
    // Refused here (not just hidden in the UI) so no API call can add one.
    // Existing custom stages are retired at startup
    // (LegacyStageRetirement): their Opportunities are moved into the main
    // stages and the rows are kept, inactive, for history.
    static final String CUSTOM_STAGES_WITHDRAWN_MESSAGE =
            "New sales stages cannot be added. Opportunities use the five main stages "
                    + "(In Progress, Won, Lost, Postponed, Dropped).";

    public SalesStage create(SalesStageRequest request) {
        throw new IllegalArgumentException(CUSTOM_STAGES_WITHDRAWN_MESSAGE);
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

    // The stages that may be chosen for an Opportunity: the active main
    // stages only, in workflow order. Custom, retired (NEW) and historical
    // stages are never offered - an Opportunity already in one still shows it
    // (it is part of the Opportunity itself), and getAllIncludingInactive
    // lists every row for Admins and reports.
    public List<SalesStage> getAll() {
        return repository.findByIsActiveTrue().stream()
                .filter(OpportunityWorkflow::isMainStage)
                .sorted(java.util.Comparator.comparing(s -> s.getStageType().ordinal()))
                .toList();
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

        // Only the five main stages are ever active. A retired stage (NEW,
        // UNRESPONSIVE, INVALID, the old QUOTATION SENT / NEGOTIATION stages)
        // or a custom stage stays withdrawn from the workflow for good.
        if (!OpportunityWorkflow.isMainStage(salesStage)) {
            throw new IllegalArgumentException(
                    "\"" + salesStage.getName() + "\" has been retired and cannot be reactivated.");
        }

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
    //  - several -> for one of the five main stages (mandatory, so it must
    //    exist): the oldest row (lowest id) is tagged and a warning logged.
    //    They all carry the same name, so this is not a guess about meaning;
    //    LegacyStageRetirement then merges the others' Opportunities into it
    //    (with history) and keeps their rows inactive. For any other type,
    //    nothing is tagged (the retirements recognise those rows by name).
    // Renamed stages are found by their type, so they are never re-created.
    public void ensureCoreStages() {

        for (SalesStageType type : SalesStageType.values()) {

            List<SalesStage> typed = repository.findByStageType(type);

            if (!typed.isEmpty()) {

                // The five main stages are mandatory: one that was deactivated
                // earlier (DROPPED could be, before it became a main stage) is
                // made selectable again. Nothing else about it changes.
                if (type.isMainStage()) {
                    for (SalesStage stage : typed) {
                        if (!Boolean.TRUE.equals(stage.getIsActive())) {
                            stage.setIsActive(true);
                            repository.save(stage);
                            log.info("Main sales stage \"{}\" (id {}) reactivated", stage.getName(), stage.getId());
                        }
                    }
                }

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

            } else if (candidates.size() > 1 && type.isMainStage()) {

                SalesStage oldest = candidates.stream()
                        .min(java.util.Comparator.comparing(SalesStage::getId))
                        .orElseThrow();
                oldest.setStageType(type);
                if (!Boolean.TRUE.equals(oldest.getIsActive())) {
                    oldest.setIsActive(true);
                }
                repository.save(oldest);

                log.warn("{} stages match the name \"{}\": the oldest, \"{}\" (id {}), was tagged as main stage {}; "
                                + "the others are retired into it at startup.",
                        candidates.size(), type.getDefaultName(), oldest.getName(), oldest.getId(), type);

            } else if (candidates.size() > 1) {

                log.warn("{} untyped stages match the name \"{}\"; none tagged as {} - the startup retirement "
                        + "moves their opportunities by name.", candidates.size(), type.getDefaultName(), type);

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
