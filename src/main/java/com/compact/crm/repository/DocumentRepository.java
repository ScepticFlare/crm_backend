package com.compact.crm.repository;

import com.compact.crm.entity.Document;
import com.compact.crm.enums.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    // Backs the attachment picker: "show me every active document of this
    // category" (e.g. every COMPANY_PROFILE / GENERAL document for Keep in
    // Touch), or narrowed to a specific product's brochures.
    List<Document> findByCategoryAndIsActiveTrueOrderByFileNameAsc(DocumentType category);

    List<Document> findByCategoryAndProductIdAndIsActiveTrueOrderByFileNameAsc(DocumentType category, Long productId);

    List<Document> findByIsActiveTrueOrderByFileNameAsc();

}
