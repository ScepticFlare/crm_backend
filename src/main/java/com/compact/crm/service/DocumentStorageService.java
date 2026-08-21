package com.compact.crm.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

// Abstraction over where uploaded Document bytes actually live, so
// DocumentService/LeadEmailService/DocumentController never know or care
// whether storage is local disk, Supabase Storage, or S3 - only this
// interface and its one implementation (LocalDiskDocumentStorageService)
// do. Swapping backends later means adding a new implementation and
// changing which one is @Primary/wired, not touching any calling code.
public interface DocumentStorageService {

    // Persists the file's bytes and returns the storage key
    // (Document.storedFileName) that load()/delete() take later. Never the
    // original filename - see entity.Document for why the two are kept
    // separate.
    String store(MultipartFile file) throws IOException;

    byte[] load(String storedFileName) throws IOException;

    void delete(String storedFileName) throws IOException;

}
