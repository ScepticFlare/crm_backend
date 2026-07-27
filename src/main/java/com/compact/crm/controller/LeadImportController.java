package com.compact.crm.controller;

import com.compact.crm.service.LeadImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LeadImportController {

    private final LeadImportService leadImportService;

    @PostMapping("/import")
    public ResponseEntity<String> importLeads(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please select an Excel file.");
        }

        String result = leadImportService.importExcel(file);

        return ResponseEntity.ok(result);
    }
}