package com.openhire.integration.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookSecurityTest {
    @Test
    void signedBytesAndTimestampVerified() {
        byte[] key = new byte[32];
        String signature = WebhookSecurity.sign(key, 1000, "delivery", "body");
        assertTrue(WebhookSecurity.verify(key, 1000, "delivery", "body", signature, Instant.ofEpochSecond(1001)));
        assertFalse(WebhookSecurity.verify(key, 1000, "delivery", "tampered", signature, Instant.ofEpochSecond(1001)));
        assertFalse(WebhookSecurity.verify(key, 1000, "other", "body", signature, Instant.ofEpochSecond(1001)));
        assertFalse(WebhookSecurity.verify(key, 1000, "delivery", "body", signature, Instant.ofEpochSecond(1301)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "10.0.0.1",
                "172.16.0.1",
                "192.168.1.1",
                "169.254.169.254",
                "100.100.100.200",
                "0.0.0.0",
                "::1",
                "fc00::1",
                "fe80::1",
                "2001:db8::1",
                "2002::1"
            })
    void rejectsNonPublicAddresses(String ip) throws Exception {
        assertFalse(WebhookSecurity.publicAddress(InetAddress.getByName(ip)));
    }

    @Test
    void acceptsGlobalAddress() throws Exception {
        assertTrue(WebhookSecurity.publicAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void retryIsBoundedAcross24Hours() {
        long elapsed = 0;
        int attempts = 0;
        while (elapsed < 86400) {
            long delay = WebhookSecurity.retrySeconds(++attempts);
            assertTrue(delay >= 30 && delay <= 3600);
            elapsed += delay;
        }
        assertTrue(attempts < 40);
    }
}
