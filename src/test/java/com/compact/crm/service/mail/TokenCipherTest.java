package com.compact.crm.service.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTest {

    @Test
    void roundTrips_andNeverStoresThePlaintext() {

        TokenCipher cipher = MailTestSupport.cipher();

        String sealed = cipher.encrypt("1//refresh-token-value", "refresh:7:GOOGLE");

        assertThat(sealed).doesNotContain("refresh-token-value");
        assertThat(cipher.decrypt(sealed, "refresh:7:GOOGLE")).isEqualTo("1//refresh-token-value");

        // a fresh IV every time
        assertThat(cipher.encrypt("1//refresh-token-value", "refresh:7:GOOGLE")).isNotEqualTo(sealed);
    }

    @Test
    void aCiphertextIsBoundToItsEmployeeAndProvider() {

        TokenCipher cipher = MailTestSupport.cipher();

        String sealed = cipher.encrypt("token", "refresh:7:GOOGLE");

        // copied onto another employee's row, or relabelled as another provider: unusable
        assertThatThrownBy(() -> cipher.decrypt(sealed, "refresh:8:GOOGLE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(sealed, "refresh:7:MICROSOFT")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperingIsDetected() {

        TokenCipher cipher = MailTestSupport.cipher();
        String sealed = cipher.encrypt("token", "ctx");
        char last = sealed.charAt(sealed.length() - 3);
        String tampered = sealed.substring(0, sealed.length() - 3) + (last == 'A' ? 'B' : 'A') + sealed.substring(sealed.length() - 2);

        assertThatThrownBy(() -> cipher.decrypt(tampered, "ctx")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyRotation_readsOldValuesWithThePreviousKey_andFlagsThemForReEncryption() {

        String old = new TokenCipher(MailTestSupport.OTHER_KEY, "").encrypt("token", "ctx");

        TokenCipher rotated = new TokenCipher(MailTestSupport.KEY, MailTestSupport.OTHER_KEY);

        assertThat(rotated.decrypt(old, "ctx")).isEqualTo("token");
        assertThat(rotated.needsReEncryption(old)).isTrue();
        assertThat(rotated.needsReEncryption(rotated.encrypt("token", "ctx"))).isFalse();

        // without the previous key the old value fails loudly instead of decrypting to garbage
        assertThatThrownBy(() -> MailTestSupport.cipher().decrypt(old, "ctx"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key this server does not have");
    }

    @Test
    void aMissingOrMalformedKey_disablesTheFeature() {

        assertThat(new TokenCipher("", "").isConfigured()).isFalse();
        assertThat(new TokenCipher("not base64 !!", "").isConfigured()).isFalse();
        assertThat(new TokenCipher("c2hvcnQ=", "").isConfigured()).isFalse();   // 5 bytes

        TokenCipher off = new TokenCipher("", "");
        assertThatThrownBy(() -> off.encrypt("x", "ctx")).isInstanceOf(IllegalStateException.class);
    }
}
