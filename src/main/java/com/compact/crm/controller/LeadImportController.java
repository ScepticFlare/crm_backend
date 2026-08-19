package com.compact.crm.controller;

import com.compact.crm.service.LeadImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

// Previously had no authorization check at all beyond "authenticated" -
// any Manager/Employee could bulk-create leads via this endpoint even
// though the frontend only ever surfaced it to ADMIN. Gated the same way
// ReportController is.
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
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