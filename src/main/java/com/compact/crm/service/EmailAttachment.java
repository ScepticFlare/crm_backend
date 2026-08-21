package com.compact.crm.service;

// A file to attach to an outbound Lead email - just enough for
// BrevoEmailClient to build a Brevo API attachment (filename + base64
// content), independent of where the bytes came from (today always
// DocumentService.loadBytes, via DocumentStorageService).
record EmailAttachment(String filename, byte[] bytes) {
}
