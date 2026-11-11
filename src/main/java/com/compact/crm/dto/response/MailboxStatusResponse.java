package com.compact.crm.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

// The signed-in employee's OWN mailbox connection, for the Email Account page.
// Identity and status only - never a token, a secret or a scope string.
//   configured  the server can store mailbox credentials at all
//   providers   which providers the server is set up for, e.g. {google: true, microsoft: false}
//   canConnect  the caller's role grants EMAIL_SEND (connecting is pointless otherwise)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailboxStatusResponse(
        boolean configured,
        Map<String, Boolean> providers,
        boolean canConnect,
        boolean connected,
        String provider,
        String emailAddress,
        String status,
        boolean needsReconnect,
        String statusDetail,
        LocalDateTime connectedAt,
        LocalDateTime lastUsedAt,
        Boolean providerRevoked
) {
}
