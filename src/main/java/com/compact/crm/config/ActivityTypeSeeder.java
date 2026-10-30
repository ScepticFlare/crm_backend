package com.compact.crm.config;

import com.compact.crm.service.ActivityTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// Gives a database with NO activity types the default set (see
// ActivityTypeService.seedDefaultsIfEmpty), so follow-ups and the lead
// conversion form always have something to choose. Same startup-seeder
// pattern as SalesStageSeeder / PublicLeadIndustrySeeder. A database that
// already has any activity type is left exactly as it is.
@Component
@RequiredArgsConstructor
public class ActivityTypeSeeder implements CommandLineRunner {

    private final ActivityTypeService activityTypeService;

    @Override
    public void run(String... args) {
        activityTypeService.seedDefaultsIfEmpty();
    }
}
