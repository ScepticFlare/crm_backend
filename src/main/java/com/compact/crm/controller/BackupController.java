package com.compact.crm.controller;

import com.compact.crm.dto.response.BackupResponse;
import com.compact.crm.service.backup.BackupService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

// Database backups ("Take Backup"). ADMIN only, enforced here
// (@PreAuthorize on the class: 401 without a token, 403 for any other role)
// and again inside BackupService. POST starts a backup in the background and
// answers 202; the page polls GET /{id}. The download is streamed from the
// private backup bucket through this endpoint only - there is no public or
// pre-signed URL - with Cache-Control: no-store.
@RestController
@RequestMapping("/api/admin/backups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BackupController {

    private final BackupService backupService;

    @PostMapping
    public ResponseEntity<BackupResponse> start() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(backupService.start());
    }

    @GetMapping
    public List<BackupResponse> list() {
        return backupService.list();
    }

    @GetMapping("/{id}")
    public BackupResponse get(@PathVariable Long id) {
        return backupService.get(id);
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {

        BackupService.DownloadTicket ticket = backupService.prepareDownload(id);

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(ticket.fileName()).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (ticket.sizeBytes() != null) {
            response.setContentLengthLong(ticket.sizeBytes());
        }

        try {
            backupService.streamDownload(ticket, response.getOutputStream());
        } catch (IOException e) {
            if (!response.isCommitted()) {
                // nothing sent yet: replace the file headers with a normal JSON error (502)
                response.reset();
                throw new IllegalStateException("The backup file could not be read from backup storage. Please try again.");
            }
            // already streaming: the client sees a short file, which fails its size/SHA-256 check
            throw e;
        }
    }
}
