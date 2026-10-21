package com.compact.crm.config;

import com.compact.crm.service.SalesStageService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SalesStageSeeder implements CommandLineRunner {

    private final SalesStageService salesStageService;

    @Override
    public void run(String... args) {

        // Every seeded stage (NEW, IN PROGRESS, POSTPONED, WON, LOST and the
        // historical DROPPED / UNRESPONSIVE / INVALID) is identified by a
        // stable internal type, not by name: existing ones are tagged (never
        // duplicated, even if an Admin renamed them) and missing ones are
        // created. The historical QUOTATION SENT / NEGOTIATION stages are
        // tagged if present but not created - they are now sub-statuses of
        // IN PROGRESS.
        salesStageService.ensureCoreStages();

    }
}
