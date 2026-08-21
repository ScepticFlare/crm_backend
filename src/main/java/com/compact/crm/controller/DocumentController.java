package com.compact.crm.controller;

import com.compact.crm.entity.Document;
import com.compact.crm.enums.DocumentType;
import com.compact.crm.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

// Same authorization shape as EmailTemplateController: listing/downloading
// is open to any authenticated user (they need to see and attach documents
// when composing a Lead email), upload/deactivate are checked inside
// DocumentService (DOCUMENT_MANAGE) rather than via @PreAuthorize here.
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Document upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam DocumentType category,
            @RequestParam(required = false) Long productId
    ) {
        return documentService.upload(file, category, productId);
    }

    @GetMapping
    public List<Document> list(
            @RequestParam(required = false) DocumentType category,
            @RequestParam(required = false) Long productId
    ) {
        return documentService.list(category, productId);
    }

    @GetMapping("/{id}")
    public Document getById(@PathVariable Long id) {
        return documentService.getById(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {

        Document document = documentService.getById(id);
        byte[] bytes = documentService.loadBytes(document);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(document.getContentType()));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(document.getFileName(), StandardCharsets.UTF_8).build());

        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable Long id) {
        documentService.deactivate(id);
    }

}
