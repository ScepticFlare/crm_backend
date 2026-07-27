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

        salesStageService.findOrCreate("NEW");
        salesStageService.findOrCreate("QUOTATION SENT");
        salesStageService.findOrCreate("NEGOTIATION");
        salesStageService.findOrCreate("POSTPONED");
        salesStageService.findOrCreate("WON");
        salesStageService.findOrCreate("LOST");

    }
}