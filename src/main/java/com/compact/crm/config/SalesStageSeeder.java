package com.compact.crm.config;

import com.compact.crm.service.LegacyStageRetirement;
import com.compact.crm.service.NewStageRetirement;
import com.compact.crm.service.SalesStageService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SalesStageSeeder implements CommandLineRunner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SalesStageSeeder.class);

    private final SalesStageService salesStageService;
    private final NewStageRetirement newStageRetirement;
    private final LegacyStageRetirement legacyStageRetirement;

    @Override
    public void run(String... args) {

        // Every seeded stage (the five main stages IN PROGRESS, POSTPONED,
        // WON, LOST, DROPPED; the historical UNRESPONSIVE / INVALID only if present) is identified by a
        // stable internal type, not by name: existing ones are tagged (never
        // duplicated, even if an Admin renamed them) and missing ones are
        // created. The historical QUOTATION SENT / NEGOTIATION stages are
        // tagged if present but not created - they are now sub-statuses of
        // IN PROGRESS.
        salesStageService.ensureCoreStages();

        // Then (types now resolved) move any Opportunity still in the retired
        // NEW stage to IN PROGRESS, marking it "Previously New", and keep the
        // NEW row inactive. A no-op once done.
        // Each retirement is one transaction: on any failure it is rolled
        // back completely (no Opportunity half-moved), the error is logged
        // and the application still starts - Opportunities left in an old
        // stage keep working and are moved on the next startup.
        try {
            newStageRetirement.retireNewStage();
        } catch (RuntimeException e) {
            log.error("Retiring the NEW stage failed and was rolled back; it is retried at the next startup.", e);
        }

        // Then retire every other stage that is not one of the five main
        // stages: UNRESPONSIVE -> DROPPED, INVALID -> LOST, the old QUOTATION
        // SENT / NEGOTIATION stages and custom stages -> IN PROGRESS (with the
        // matching sub-status where unambiguous). Provenance and history are
        // kept; the stage rows stay, inactive. A no-op once done.
        try {
            legacyStageRetirement.retireLegacyStages();
        } catch (RuntimeException e) {
            log.error("Retiring the non-main stages failed and was rolled back; it is retried at the next startup.", e);
        }

    }
}
