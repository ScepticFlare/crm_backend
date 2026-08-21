package com.compact.crm.service;

import com.compact.crm.entity.Document;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Product;
import com.compact.crm.enums.DocumentType;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.DocumentRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static com.compact.crm.security.AccessControlService.DOCUMENT_MANAGE;

// Metadata CRUD + upload/download orchestration for attachable documents
// (product brochures, company profile, general files). Actual file bytes
// are delegated to DocumentStorageService throughout - this class never
// touches java.io/java.nio directly. Listing/downloading is open to any
// authenticated user (they need to see and attach documents when composing
// a Lead email); only upload/deactivate require DOCUMENT_MANAGE
// (ADMIN-only, see V6 migration) - same shape as EmailTemplateService.
@Service
@RequiredArgsConstructor
public class DocumentService {

    // A generous but real cap - most SMTP providers (including sandboxes
    // like Mailtrap) reject messages well before this; failing fast here
    // gives a clear reason instead of an opaque mail-send error later.
    private static final long MAX_FILE_SIZE_BYTES = 15L * 1024 * 1024;

    private final DocumentRepository documentRepository;
    private final ProductRepository productRepository;
    private final DocumentStorageService documentStorageService;
    private final AccessControlService accessControlService;
    private final CurrentUserService currentUserService;

    public Document upload(MultipartFile file, DocumentType category, Long productId) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, DOCUMENT_MANAGE);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please select a file to upload.");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File is too large (maximum 15 MB).");
        }

        Product product = null;

        if (productId != null) {
            product = productRepository.findById(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        }

        String storedFileName;

        try {
            storedFileName = documentStorageService.store(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to store the uploaded file.", e);
        }

        Document document = Document.builder()
                .fileName(file.getOriginalFilename())
                .storedFileName(storedFileName)
                .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                .fileSizeBytes(file.getSize())
                .category(category)
                .product(product)
                .uploadedByEmployee(currentEmployee)
                .uploadedByName(currentEmployee.getName())
                .isActive(true)
                .build();

        return documentRepository.save(document);
    }

    public List<Document> list(DocumentType category, Long productId) {

        if (category == null) {
            return documentRepository.findByIsActiveTrueOrderByFileNameAsc();
        }

        if (productId != null) {
            return documentRepository.findByCategoryAndProductIdAndIsActiveTrueOrderByFileNameAsc(category, productId);
        }

        return documentRepository.findByCategoryAndIsActiveTrueOrderByFileNameAsc(category);
    }

    public Document getById(Long id) {

        return documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    public byte[] loadBytes(Document document) {

        try {
            return documentStorageService.load(document.getStoredFileName());
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the stored file.", e);
        }
    }

    // Soft delete only, matching the Product/Industry master-data
    // convention - the underlying file is deliberately left on disk (an
    // already-sent email may still reference it, and there is no hard
    // delete anywhere else in this codebase for master data either).
    public void deactivate(Long id) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, DOCUMENT_MANAGE);

        Document document = getById(id);
        document.setIsActive(false);

        documentRepository.save(document);
    }

}
