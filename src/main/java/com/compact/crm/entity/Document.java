package com.compact.crm.entity;

import com.compact.crm.enums.DocumentType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// Metadata for an uploaded file attachable to Lead emails (product
// brochures, the company profile, or general documents). The actual file
// bytes never live in this row or in the database - storedFileName is the
// key service.DocumentStorageService uses to read/write the bytes on
// whatever backing store is configured (local disk today, see
// crm.documents.storage-path; swappable for Supabase/S3 later without
// changing this entity). product is only ever set for PRODUCT_BROCHURE
// documents, letting the email composer auto-suggest the right brochure for
// a Lead's interested product(s).
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Original filename as uploaded, shown to users and used as the
    // attachment filename when the email is sent.
    @Column(name = "file_name", nullable = false)
    private String fileName;

    // Disk-safe generated name (e.g. a UUID + original extension) - the key
    // DocumentStorageService actually stores/loads bytes under. Never
    // shown to users, never guessable from fileName.
    @Column(name = "stored_file_name", nullable = false, unique = true)
    private String storedFileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentType category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_employee_id")
    @JsonIgnore
    private Employee uploadedByEmployee;

    @Column(name = "uploaded_by_name", nullable = false)
    private String uploadedByName;

    @Builder.Default
    private Boolean isActive = true;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {

        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }

        if (isActive == null) {
            isActive = true;
        }
    }

    @JsonProperty("uploadedByEmployeeId")
    public Long getUploadedByEmployeeId() {
        return uploadedByEmployee != null ? uploadedByEmployee.getId() : null;
    }
}
