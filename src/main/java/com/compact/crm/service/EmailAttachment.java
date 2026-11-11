package com.compact.crm.service;

// A file to attach to an outbound email - filename + bytes, independent of
// where the bytes came from (today always DocumentService.loadBytes, via
// DocumentStorageService). Turned into a MIME part (Gmail) or a Graph
// fileAttachment (Microsoft) by the provider in service.mail.
public record EmailAttachment(String filename, byte[] bytes) {
}
