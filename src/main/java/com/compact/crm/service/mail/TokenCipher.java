package com.compact.crm.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// Encrypts mailbox refresh tokens (and the short-lived PKCE verifier) at rest
// with AES-256-GCM. The key is EMAIL_TOKEN_ENCRYPTION_KEY: 32 random bytes,
// base64-encoded, held only in the Render environment (never in the database
// or the repository). Each value is "<key id>.<base64(iv | ciphertext+tag)>",
// where the key id is the first 8 hex characters of SHA-256(key) - so a value
// can be decrypted after a key rotation with EMAIL_TOKEN_ENCRYPTION_KEY_PREVIOUS,
// and a value written under an unknown key fails loudly instead of decrypting
// to garbage. Every value is bound to its context (associated data such as
// "refresh:<employee id>:<provider>"), so a ciphertext copied onto another
// employee's row does not decrypt.
//
// Without a valid key the mailbox feature is "not configured": connecting and
// sending are refused with 503, and the rest of the CRM runs normally.
@Slf4j
@Component
public class TokenCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    private final SecretKey current;
    private final String currentId;
    private final SecretKey previous;
    private final String previousId;

    @Autowired
    public TokenCipher(
            @Value("${crm.mail.token-encryption-key:}") String key,
            @Value("${crm.mail.token-encryption-key-previous:}") String previousKey
    ) {
        this.current = parse(key, "EMAIL_TOKEN_ENCRYPTION_KEY");
        this.currentId = current == null ? null : keyId(current);
        this.previous = parse(previousKey, "EMAIL_TOKEN_ENCRYPTION_KEY_PREVIOUS");
        this.previousId = previous == null ? null : keyId(previous);

        if (current == null) {
            log.warn("Mailbox email is DISABLED: EMAIL_TOKEN_ENCRYPTION_KEY is not set (or not 32 base64-encoded bytes). "
                    + "The CRM runs normally, but connecting a mailbox and sending email are refused until it is set.");
        }
    }

    public boolean isConfigured() {
        return current != null;
    }

    public String encrypt(String plaintext, String context) {

        requireKey();

        try {

            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, current, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));

            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();

            return currentId + "." + Base64.getEncoder().encodeToString(out);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the mailbox credential.", e);
        }
    }

    public String decrypt(String value, String context) {

        requireKey();

        int dot = value == null ? -1 : value.indexOf('.');

        if (dot <= 0) {
            throw new IllegalStateException("The stored mailbox credential is malformed.");
        }

        String id = value.substring(0, dot);

        SecretKey key = id.equals(currentId) ? current : id.equals(previousId) ? previous : null;

        if (key == null) {
            throw new IllegalStateException("The stored mailbox credential was encrypted with a key this server does not have.");
        }

        try {

            byte[] in = Base64.getDecoder().decode(value.substring(dot + 1));

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));

            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);

        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The stored mailbox credential could not be decrypted.", e);
        }
    }

    // True when the value was written under the previous key and should be
    // re-encrypted with the current one at the next opportunity.
    public boolean needsReEncryption(String value) {
        return value != null && currentId != null && !value.startsWith(currentId + ".");
    }

    private void requireKey() {
        if (current == null) {
            throw new IllegalStateException("EMAIL_TOKEN_ENCRYPTION_KEY is not configured.");
        }
    }

    private static SecretKey parse(String base64, String name) {

        if (base64 == null || base64.isBlank()) {
            return null;
        }

        try {

            byte[] bytes = Base64.getDecoder().decode(base64.trim());

            if (bytes.length != 32) {
                log.warn("{} must be 32 bytes, base64-encoded (it decodes to {} bytes) - it is ignored.", name, bytes.length);
                return null;
            }

            return new SecretKeySpec(bytes, "AES");

        } catch (IllegalArgumentException e) {
            log.warn("{} is not valid base64 - it is ignored.", name);
            return null;
        }
    }

    private static String keyId(SecretKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
