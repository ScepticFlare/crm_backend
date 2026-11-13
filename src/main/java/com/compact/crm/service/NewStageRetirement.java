package com.compact.crm.service;

import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
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
import java.util.List;

// Retires the NEW opportunity stage (SalesStageType.NEW is retired):
//  - every Opportunity still in a NEW-typed stage is moved to IN PROGRESS and
//    stamped with migrated_from_new_at ("Previously New"), in one bulk update
//    that touches only those two columns;
//  - each move is written to the Opportunity's history as a Stage change
//    (NEW -> IN PROGRESS) by "System";
//  - the NEW stage row itself is kept (history and old activity entries refer
//    to it) but made inactive.
// Runs at startup right after SalesStageService.ensureCoreStages has resolved
// the stage types (config.SalesStageSeeder), so the stages are found by their
// stable type, never by name. Idempotent: once moved, nothing is left in NEW
// and NEW can never be selected again, so later startups do nothing.
//
// Nothing is guessed: without exactly one IN PROGRESS stage nothing is moved
// and an error is logged for an Admin to resolve.
@Service
@RequiredArgsConstructor
public class NewStageRetirement {

    private static final Logger log = LoggerFactory.getLogger(NewStageRetirement.class);

    static final String SYSTEM_ACTOR = "System (NEW stage retired)";

    private final SalesStageRepository salesStageRepository;
    private final OpportunityRepository opportunityRepository;
    private final ActivityLogRepository activityLogRepository;

    // Returns how many Opportunities were moved.
    @Transactional
    public int retireNewStage() {

        // The NEW-typed row, plus any untyped row whose name is "New" (one that
        // stayed untyped because another row already carries the NEW type, or
        // several rows shared the name) - it is the same retired stage.
        List<SalesStage> newStages = new java.util.ArrayList<>(salesStageRepository.findByStageType(SalesStageType.NEW));
        salesStageRepository.findByStageTypeIsNull().stream()
                .filter(st -> LegacyStageRetirement.typeNamed(st.getName()) == SalesStageType.NEW)
                .forEach(newStages::add);

        if (newStages.isEmpty()) {
            return 0;
        }

        List<SalesStage> inProgress = salesStageRepository.findByStageType(SalesStageType.IN_PROGRESS);

        if (inProgress.size() != 1) {
            log.error("NEW stage not retired: expected exactly one IN PROGRESS stage, found {} - resolve manually.",
                    inProgress.size());
            return 0;
        }

        SalesStage target = inProgress.get(0);
        LocalDateTime movedAt = LocalDateTime.now();
        int moved = 0;

        for (SalesStage newStage : newStages) {

            List<Object[]> affected = opportunityRepository.findIdAndTitleBySalesStageId(newStage.getId());

            if (!affected.isEmpty()) {

                int updated = opportunityRepository.moveFromRetiredNewStage(newStage.getId(), target, movedAt);

                for (Object[] row : affected) {
                    activityLogRepository.save(ActivityLog.builder()
                            .employee(null)
                            .employeeName(SYSTEM_ACTOR)
                            .module(ActivityModule.OPPORTUNITY)
                            .action(ActivityAction.UPDATE)
                            .entityId((Long) row[0])
                            .entityName((String) row[1])
                            .description("Stage: " + newStage.getName() + " → " + target.getName()
                                    + " (the NEW stage was retired; marked Previously New)")
                            .fieldName("salesStage")
                            .oldValue(newStage.getName())
                            .newValue(target.getName())
                            .createdAt(movedAt)
                            .build());
                }

                moved += updated;

                log.info("Moved {} opportunit(ies) from retired stage \"{}\" (id {}) to \"{}\" (id {})",
                        updated, newStage.getName(), newStage.getId(), target.getName(), target.getId());
            }

            if (!Boolean.FALSE.equals(newStage.getIsActive())) {
                newStage.setIsActive(false);
                salesStageRepository.save(newStage);
                log.info("Retired sales stage \"{}\" (id {}) deactivated", newStage.getName(), newStage.getId());
            }
        }

        return moved;
    }
}
