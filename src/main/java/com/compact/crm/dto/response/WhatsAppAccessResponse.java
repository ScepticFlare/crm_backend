package com.compact.crm.dto.response;

import com.compact.crm.enums.Scope;

// GET /api/bulk-whatsapp/access - drives whether the Bulk WhatsApp action is
// shown and enabled. Provider details are only filled for callers allowed to send.
//   canSend        the caller holds WHATSAPP_SEND
//   configured     WAHA base URL + API key present and the feature enabled
//   sessionStatus  provider session state (WORKING, SCAN_QR_CODE, ...), or null
//   ready          configured AND the session can send
//   message        non-sensitive explanation when not ready
public record WhatsAppAccessResponse(
        boolean canSend,
        Scope scope,
        boolean configured,
        String sessionStatus,
        boolean ready,
        String message,
        int maxRecipients,
        int chunkSize
) {
}
