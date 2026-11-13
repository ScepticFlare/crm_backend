package com.compact.crm.service;

import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.OpportunitySubStatus;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.SalesStageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Completes the five-main-stage workflow by retiring every remaining stage
// that is not one of the five main stages (NEW has its own retirement,
// service.NewStageRetirement). Approved mapping:
//  - UNRESPONSIVE                 -> DROPPED
//  - INVALID                      -> LOST
//  - old QUOTATION SENT stage     -> IN PROGRESS, sub-status Quotation Sent
//  - old NEGOTIATION stage        -> IN PROGRESS, sub-status Negotiation
//  - custom (untyped) stage whose name IS a stage name (e.g. a duplicate
//    "Won" or "Unresponsive" that stayed untyped because another row already
//    carries that type) -> that stage's place in the five-stage model
//    (Won -> WON, Unresponsive -> DROPPED, Quotation Sent -> IN PROGRESS /
//    Quotation Sent ...). A custom "New" is left to NewStageRetirement.
//  - any other custom (untyped) stage -> IN PROGRESS; with the Quotation
//    Sent / Negotiation sub-status only when its name unambiguously means
//    that (see subStatusForCustomName). Never an outcome (Won / Lost / ...)
//    unless its name is exactly that outcome.
//  - an Opportunity with no stage at all -> IN PROGRESS (the old edit form
//    treated a missing stage as NEW, which is now IN PROGRESS).
//
// For each moved Opportunity: one bulk update changes only the stage, the
// sub-status and the provenance columns (retired_stage_type / _name /
// _moved_at), so updatedAt and every other field and relationship are
// untouched; the move is written to its history (old stage name kept) by
// "System". Every retired stage row is kept, inactive, for referential
// integrity and history.
//
// Nothing is guessed:
//  - targets are found by their stable type, never by name;
//  - without exactly one target main stage, that source is left alone and an
//    error is logged (only possible after manual database edits - startup
//    seeding always leaves exactly one row of each main stage).
// Concurrency: the Opportunities of a source are read with a row lock
// (SELECT ... FOR UPDATE), so the bulk update changes exactly the rows the
// history is written for, and a second instance starting at the same time
// finds them already moved. Everything runs in one transaction: a failure
// rolls the whole retirement back (see config.SalesStageSeeder).
// Idempotent: once moved, the retired stages are empty and can never be
// selected again, so later startups only re-confirm they are inactive.
@Service
@RequiredArgsConstructor
public class LegacyStageRetirement {

    private static final Logger log = LoggerFactory.getLogger(LegacyStageRetirement.class);

    static final String SYSTEM_ACTOR = "System (stage retired)";

    // Custom stage names that unambiguously mean one IN PROGRESS sub-status,
    // compared after normalizeLoose (case, punctuation, spacing ignored).
    private static final Set<String> QUOTATION_NAMES = Set.of(
            "QUOTATION", "QUOTATION SENT", "QUOTATIONS SENT", "QUOTATION SUBMITTED", "QUOTATION GIVEN",
            "QUOTE", "QUOTE SENT", "QUOTES SENT", "QUOTE SUBMITTED", "QUOTE GIVEN", "QUOTED");

    private static final Set<String> NEGOTIATION_NAMES = Set.of(
            "NEGOTIATION", "NEGOTIATIONS", "NEGOTIATING", "IN NEGOTIATION", "UNDER NEGOTIATION");

    private final SalesStageRepository salesStageRepository;
    private final OpportunityRepository opportunityRepository;
    private final ActivityLogRepository activityLogRepository;

    // Returns how many Opportunities were moved.
    @Transactional
    public int retireLegacyStages() {

        Map<SalesStageType, SalesStage> targets = new EnumMap<>(SalesStageType.class);

        // every main stage can be a target (a custom duplicate "Won" goes to WON)
        for (SalesStageType type : SalesStageType.values()) {

            if (!type.isMainStage()) {
                continue;
            }

            List<SalesStage> found = salesStageRepository.findByStageType(type);

            if (found.size() == 1) {
                targets.put(type, found.get(0));
            } else {
                log.error("Expected exactly one {} stage, found {} - stages that move into it are not retired; "
                        + "resolve manually.", type, found.size());
            }
        }

        List<SalesStage> sources = new ArrayList<>();

        for (SalesStageType type : SalesStageType.values()) {
            if (type.retiredInto() != null) {
                sources.addAll(salesStageRepository.findByStageType(type));
            }
        }

        sources.addAll(salesStageRepository.findByStageTypeIsNull());

        // a fixed order, so concurrent startups lock rows in the same order
        sources.sort(java.util.Comparator.comparing(SalesStage::getId));

        LocalDateTime movedAt = LocalDateTime.now();
        int moved = 0;

        for (SalesStage source : sources) {

            Move move = planFor(source);

            if (move == null) {
                continue;
            }

            SalesStage target = targets.get(move.targetType());

            if (target == null) {
                continue;
            }

            moved += move(source, target, move, movedAt);

            if (!Boolean.FALSE.equals(source.getIsActive())) {
                source.setIsActive(false);
                salesStageRepository.save(source);
                log.info("Retired sales stage \"{}\" (id {}) deactivated", source.getName(), source.getId());
            }
        }

        moved += moveOpportunitiesWithoutAStage(targets.get(SalesStageType.IN_PROGRESS), movedAt);

        return moved;
    }

    // An Opportunity with no stage at all -> IN PROGRESS (sub-status kept).
    private int moveOpportunitiesWithoutAStage(SalesStage inProgress, LocalDateTime movedAt) {

        if (inProgress == null) {
            return 0;
        }

        List<Object[]> affected = opportunityRepository.findIdAndTitleWithoutSalesStage();

        if (affected.isEmpty()) {
            return 0;
        }

        int updated = opportunityRepository.moveOpportunitiesWithoutStage(inProgress, movedAt);

        for (Object[] row : affected) {
            activityLogRepository.save(entry((Long) row[0], (String) row[1], movedAt, "salesStage", null,
                    inProgress.getName(), "Stage: - → " + inProgress.getName()
                            + " (the opportunity had no stage; the five-main-stage workflow was completed)"));
        }

        log.info("Moved {} opportunit(ies) without a stage to \"{}\" (id {})",
                updated, inProgress.getName(), inProgress.getId());

        return updated;
    }

    // Where a retired stage's Opportunities go. `setSubStatus` false keeps the
    // stored sub-status as it is. `recordedType` is the retired type stored as
    // provenance (null for a custom stage that names no retired type). A null
    // plan: leave this stage alone.
    record Move(SalesStageType targetType, boolean setSubStatus, OpportunitySubStatus subStatus,
                SalesStageType recordedType) {
    }

    static Move planFor(SalesStage source) {

        SalesStageType type = source.getStageType();

        if (type != null) {

            SalesStageType into = type.retiredInto();

            // Main stages and NEW (its own retirement) are not moved here.
            return into == null ? null : new Move(into, true, type.retiredIntoSubStatus(), type);
        }

        // A custom stage whose name is exactly a stage name means that stage:
        // it stayed untyped only because another row already carries the type
        // (or several rows shared the name).
        SalesStageType named = typeNamed(source.getName());

        if (named == SalesStageType.NEW) {
            return null;    // NewStageRetirement moves it and marks it Previously New
        }

        if (named != null) {

            SalesStageType into = named.isMainStage() ? named : named.retiredInto();
            SalesStageType recorded = named.isRetired() ? named : null;

            if (into == SalesStageType.IN_PROGRESS && named.retiredIntoSubStatus() == null) {
                return new Move(into, false, null, recorded);    // keeps its sub-status
            }

            // Only IN PROGRESS has a sub-status: any other target clears it.
            return new Move(into, true, named.retiredIntoSubStatus(), recorded);
        }

        OpportunitySubStatus sub = subStatusForCustomName(source.getName());

        return sub != null
                ? new Move(SalesStageType.IN_PROGRESS, true, sub, null)
                : new Move(SalesStageType.IN_PROGRESS, false, null, null);
    }

    // The stage type whose default name this name is (case, punctuation and
    // spacing ignored), else null.
    static SalesStageType typeNamed(String name) {

        String n = normalizeLoose(name);

        for (SalesStageType t : SalesStageType.values()) {
            if (normalizeLoose(t.getDefaultName()).equals(n)) {
                return t;
            }
        }

        return null;
    }

    // The sub-status a custom stage's name unambiguously means, else null.
    static OpportunitySubStatus subStatusForCustomName(String name) {

        String n = normalizeLoose(name);

        if (QUOTATION_NAMES.contains(n)) return OpportunitySubStatus.QUOTATION_SENT;
        if (NEGOTIATION_NAMES.contains(n)) return OpportunitySubStatus.NEGOTIATION;

        return null;
    }

    // Upper case; anything but letters and digits becomes a single space.
    static String normalizeLoose(String name) {
        return name == null ? "" : name.toUpperCase().replaceAll("[^A-Z0-9]+", " ").trim();
    }

    private int move(SalesStage source, SalesStage target, Move move, LocalDateTime movedAt) {

        // row-locked: see the class comment
        List<Object[]> affected = opportunityRepository.findIdTitleAndSubStatusBySalesStageId(source.getId());

        if (affected.isEmpty()) {
            return 0;
        }

        int updated = move.setSubStatus()
                ? opportunityRepository.moveFromRetiredStageSettingSubStatus(
                        source.getId(), target, move.subStatus(), move.recordedType(), source.getName(), movedAt)
                : opportunityRepository.moveFromRetiredStageKeepingSubStatus(
                        source.getId(), target, move.recordedType(), source.getName(), movedAt);

        String why = source.getStageType() == null
                ? "the custom stage \"" + source.getName() + "\" was retired"
                : "the " + source.getName() + " stage was retired";

        for (Object[] row : affected) {

            Long id = (Long) row[0];
            String title = (String) row[1];
            OpportunitySubStatus oldSub = (OpportunitySubStatus) row[2];

            activityLogRepository.save(entry(id, title, movedAt, "salesStage", source.getName(), target.getName(),
                    "Stage: " + source.getName() + " → " + target.getName() + " (" + why + ")"));

            OpportunitySubStatus newSub = move.setSubStatus() ? move.subStatus() : oldSub;

            if (!Objects.equals(oldSub, newSub)) {
                activityLogRepository.save(entry(id, title, movedAt, "subStatus",
                        oldSub == null ? null : oldSub.name(), newSub == null ? null : newSub.name(),
                        "Sub-status: " + (oldSub == null ? "-" : oldSub.name()) + " → "
                                + (newSub == null ? "-" : newSub.name()) + " (" + why + ")"));
            }
        }

        log.info("Moved {} opportunit(ies) from retired stage \"{}\" (id {}) to \"{}\" (id {}){}",
                updated, source.getName(), source.getId(), target.getName(), target.getId(),
                move.setSubStatus() && move.subStatus() != null ? ", sub-status " + move.subStatus() : "");

        return updated;
    }

    private static ActivityLog entry(Long id, String title, LocalDateTime at, String field,
                                     String oldValue, String newValue, String description) {

        return ActivityLog.builder()
                .employee(null)
                .employeeName(SYSTEM_ACTOR)
                .module(ActivityModule.OPPORTUNITY)
                .action(ActivityAction.UPDATE)
                .entityId(id)
                .entityName(title)
                .description(description)
                .fieldName(field)
                .oldValue(oldValue)
                .newValue(newValue)
                .createdAt(at)
                .build();
    }
}
