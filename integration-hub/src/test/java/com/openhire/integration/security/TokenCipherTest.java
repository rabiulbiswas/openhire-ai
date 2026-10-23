package com.openhire.integration.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TokenCipherTest {
    final TokenCipher cipher = new TokenCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void roundTripAndRandomNonces() {
        String first = cipher.encrypt("tenant:connector", "sensitive-token");
        assertEquals("sensitive-token", cipher.decrypt("tenant:connector", first));
        assertNotEquals(first, cipher.encrypt("tenant:connector", "sensitive-token"));
        assertFalse(first.contains("sensitive-token"));
    }

    @Test
    void rejectsCrossTenantCiphertext() {
        String encrypted = cipher.encrypt("tenant1:connector", "token");
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("tenant2:connector", encrypted));
    }

    @Test
    void tamperingRejected() {
        byte[] encrypted = Base64.getDecoder().decode(cipher.encrypt("tenant", "token"));
        encrypted[15] ^= 1;
        assertThrows(
                IllegalStateException.class,
                () -> cipher.decrypt("tenant", Base64.getEncoder().encodeToString(encrypted)));
    }

    @Test
    void missingKeyIsExplicitlyUnavailable() {
        assertThrows(ResponseStatusException.class, () -> new TokenCipher("").encrypt("tenant", "token"));
    }
}
