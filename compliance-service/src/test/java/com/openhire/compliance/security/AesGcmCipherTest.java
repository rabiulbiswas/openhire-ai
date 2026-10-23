package com.openhire.compliance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AesGcmCipherTest {
    private final AesGcmCipher cipher = new AesGcmCipher("test-data-key-for-compliance");

    @Test
    void roundTripsAndTheCiphertextDiffersFromThePlaintext() {
        var plain = "{\"gender\":\"FEMALE\",\"ethnicity\":\"ASIAN\"}";
        var sealed = cipher.encrypt(plain, "t1|app_1");

        assertThat(sealed).isNotEqualTo(plain).doesNotContain("FEMALE").startsWith("v1:");
        assertThat(cipher.decrypt(sealed, "t1|app_1")).isEqualTo(plain);
    }

    @Test
    void eachRecordGetsItsOwnRandomIv() {
        assertThat(cipher.encrypt("same", "ctx")).isNotEqualTo(cipher.encrypt("same", "ctx"));
    }

    @Test
    void aCiphertextCannotBeMovedToAnotherRecordOrReadWithAnotherKey() {
        var sealed = cipher.encrypt("secret", "t1|app_1");
        assertThatThrownBy(() -> cipher.decrypt(sealed, "t1|app_2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmCipher("another-key").decrypt(sealed, "t1|app_1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        var sealed = cipher.encrypt("secret", "ctx");
        var bytes = java.util.Base64.getDecoder().decode(sealed.substring(3));
        bytes[bytes.length - 1] ^= 1;
        var forged = "v1:" + java.util.Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> cipher.decrypt(forged, "ctx")).isInstanceOf(IllegalStateException.class);
    }
}
