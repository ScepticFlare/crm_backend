package com.compact.crm.service.mail;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

// Fixtures for the mailbox tests. Nothing here talks to a real provider.
final class MailTestSupport {

    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    static final String OTHER_KEY = "HxwdGhsYGRYXFBUSExAREA8ODA0KCwgJBgcEBQIDAAE=";

    private MailTestSupport() {
    }

    static MailboxProperties properties() {
        return properties("common", true);
    }

    static MailboxProperties properties(String tenant, boolean requireMatch) {
        return new MailboxProperties(
                "google-client", "google-secret", "https://crm.test/api/email-accounts/oauth/google/callback",
                "ms-client", "ms-secret", tenant, "https://crm.test/api/email-accounts/oauth/microsoft/callback",
                "https://app.crm.test/email-account", requireMatch, 10,
                0, 0, 400, 0, 0, 2000, 12000);
    }

    static TokenCipher cipher() {
        return new TokenCipher(KEY, "");
    }

    // An unsigned JWT (header.payload.signature) - the code under test reads
    // ID tokens only from the provider's token endpoint and never checks signatures.
    static String idToken(Map<String, Object> claims) {
        try {
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = enc.encodeToString(new ObjectMapper().writeValueAsBytes(claims));
            return header + "." + payload + ".c2ln";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> googleClaims(String sub, String email, String nonce) {
        Map<String, Object> c = new HashMap<>();
        c.put("iss", "https://accounts.google.com");
        c.put("aud", "google-client");
        c.put("sub", sub);
        c.put("email", email);
        c.put("email_verified", true);
        c.put("exp", Instant.now().plusSeconds(3600).getEpochSecond());
        if (nonce != null) {
            c.put("nonce", nonce);
        }
        return c;
    }

    static Map<String, Object> microsoftClaims(String oid, String tid, String nonce) {
        Map<String, Object> c = new HashMap<>();
        c.put("iss", "https://login.microsoftonline.com/" + tid + "/v2.0");
        c.put("aud", "ms-client");
        c.put("oid", oid);
        c.put("tid", tid);
        c.put("exp", Instant.now().plusSeconds(3600).getEpochSecond());
        if (nonce != null) {
            c.put("nonce", nonce);
        }
        return c;
    }
}
