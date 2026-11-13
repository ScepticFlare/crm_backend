package com.compact.crm.service;

// The only thing the CRM's WhatsApp business logic knows about the provider.
// Today's implementation is WahaWhatsAppClient (self-hosted WAHA, WhatsApp-Web
// based); moving to the official Meta Cloud API later means a new
// implementation of this interface, not a rewrite of BulkWhatsAppService.
//
// Contract:
//  - never throws for a send: every attempt is classified as ACCEPTED (2xx -
//    accepted for sending, NOT delivered), FAILED (definitely not sent, safe to
//    retry) or UNKNOWN (may or may not have been sent - never retried);
//  - never logs or returns secrets, full phone numbers or message text.
public interface WhatsAppClient {

    // Credentials / base URL present and the feature is enabled.
    boolean isConfigured();

    // Throws IntegrationNotConfiguredException (HTTP 503) when not configured.
    void requireConfigured();

    // Current state of the linked WhatsApp session (provider wording, e.g.
    // WORKING, SCAN_QR_CODE, FAILED), or UNREACHABLE / NOT_CONFIGURED.
    // May be cached for a few seconds.
    String sessionStatus();

    // True only when configured AND the session reports it can send.
    boolean isReady();

    // Throws IntegrationNotConfiguredException (HTTP 503) unless isReady().
    void requireReady();

    // `toDigits` is the normalized international number (WhatsAppNumbers).
    SendOutcome sendText(String toDigits, String text);

    enum Result { ACCEPTED, FAILED, UNKNOWN }

    // providerMessageId: the provider's id for the message when it returned one
    // (used to match delivery-status webhooks); null otherwise.
    // pauseSending: the failure is about the provider/session, not this
    // recipient - the caller should stop sending the rest of the chunk.
    record SendOutcome(Result result, String providerMessageId, Integer httpStatus, String detail,
                       boolean pauseSending) {
    }
}
