package com.compact.crm.config;

import com.compact.crm.service.IndustryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// Guarantees a stable "Other / Not Listed" Industry row exists from boot,
// the same idempotent findOrCreate pattern PublicLeadSourceSeeder uses for
// the Website Form / Brochure QR Lead Sources. The public lead form uses
// this row (never a newly-created Industry) when a visitor's real industry
// isn't in the master list - see service.PublicLeadService. Admins can
// still rename/deactivate it afterward like any other Industry - this only
// ensures it exists, it doesn't own it going forward.
@Component
@RequiredArgsConstructor
public class PublicLeadIndustrySeeder implements CommandLineRunner {

    public static final String OTHER_INDUSTRY_NAME = "Other / Not Listed";

    private final IndustryService industryService;

    @Override
    public void run(String... args) {
        industryService.findOrCreate(OTHER_INDUSTRY_NAME);
    }
}
