package com.compact.crm.config;

import com.compact.crm.service.LeadSourceMasterService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// Guarantees the two LeadSourceMaster rows the public lead form relies on
// (see service.PublicLeadService) exist from boot, the same idempotent
// findOrCreate pattern SalesStageSeeder uses for Sales Stages. Admins can
// still rename/deactivate these rows afterward like any other Lead Source -
// this only ensures they exist, it doesn't own them going forward.
@Component
@RequiredArgsConstructor
public class PublicLeadSourceSeeder implements CommandLineRunner {

    public static final String WEBSITE_SOURCE_NAME = "Website Form";
    public static final String BROCHURE_SOURCE_NAME = "Brochure QR";

    private final LeadSourceMasterService leadSourceMasterService;

    @Override
    public void run(String... args) {

        leadSourceMasterService.findOrCreate(WEBSITE_SOURCE_NAME);
        leadSourceMasterService.findOrCreate(BROCHURE_SOURCE_NAME);
    }
}
