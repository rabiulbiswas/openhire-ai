package com.openhire.integration.domain;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSecurity {
    private WebhookSecurity() {}

    public static String sign(byte[] key, long timestamp, String deliveryId, String body) {
        if (key.length < 32) throw new IllegalArgumentException("Webhook signing key must contain at least 32 bytes");
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal((timestamp + "." + deliveryId + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean verify(
            byte[] key, long timestamp, String deliveryId, String body, String signature, Instant now) {
        if (timestamp < now.getEpochSecond() - 300 || timestamp > now.getEpochSecond() + 300 || signature == null)
            return false;
        return MessageDigest.isEqual(
                sign(key, timestamp, deliveryId, body).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, second = b[1] & 255;
            return a != 0
                    && a != 10
                    && a != 127
                    && a < 224
                    && !(a == 100 && second >= 64 && second <= 127)
                    && !(a == 169 && second == 254)
                    && !(a == 172 && second >= 16 && second <= 31)
                    && !(a == 192 && (second == 168 || second == 0 || second == 2))
                    && !(a == 198 && (second == 18 || second == 19 || second == 51))
                    && !(a == 203 && second == 0);
        }
        // Only global-unicast IPv6; reject transition/tunnel/documentation ranges as well.
        return (b[0] & 0xe0) == 0x20
                && !(b[0] == 0x20 && b[1] == 0x02)
                && !(b[0] == 0x20 && b[1] == 0x01 && (b[2] == 0 || (b[2] == 0x0d && b[3] == (byte) 0xb8)));
    }

    public static long retrySeconds(int attempt) {
        return Math.min(3600, 30L << Math.min(Math.max(attempt - 1, 0), 7));
    }
}
