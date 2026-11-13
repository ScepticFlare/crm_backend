package com.compact.crm.controller;

import com.compact.crm.service.WhatsAppWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Public endpoint WAHA posts webhooks to. Unauthenticated at the HTTP layer
// (see SecurityConfig) and authenticated instead by WAHA's HMAC signature over
// the RAW body, verified before anything is parsed. Responses carry no
// internal detail.
//   503  no HMAC key configured on this server - nothing is processed
//   401  missing / wrong signature - nothing is processed
//   413  oversized body
//   400  signed but not a usable event
//   200  processed, duplicate or ignored
@RestController
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private static final int MAX_BODY_BYTES = 1_000_000;

    private final WhatsAppWebhookService webhookService;

    @PostMapping("/api/public/whatsapp/webhook")
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Webhook-Hmac", required = false) String signature,
            @RequestHeader(value = "X-Webhook-Hmac-Algorithm", required = false) String algorithm) {

        if (body != null && body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("result", "too_large"));
        }

        WhatsAppWebhookService.Verdict verdict = webhookService.verify(body, signature, algorithm);

        if (verdict == WhatsAppWebhookService.Verdict.REJECTED_NOT_CONFIGURED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("result", "not_configured"));
        }

        if (verdict != WhatsAppWebhookService.Verdict.OK) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("result", "rejected"));
        }

        WhatsAppWebhookService.Outcome outcome = webhookService.handle(body);

        if (outcome == WhatsAppWebhookService.Outcome.INVALID) {
            return ResponseEntity.badRequest().body(Map.of("result", "invalid"));
        }

        return ResponseEntity.ok(Map.of("result", outcome.name().toLowerCase()));
    }
}
