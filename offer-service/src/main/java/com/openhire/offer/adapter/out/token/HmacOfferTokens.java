package com.openhire.offer.adapter.out.token;

import com.openhire.offer.application.port.OfferPorts.OfferTokens;
import com.openhire.offer.application.port.OfferPorts.TokenClaims;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Self-contained, tamper-evident offer link: base64url(tenant|offer|expiry).base64url(HMAC-SHA256). */
@Component
class HmacOfferTokens implements OfferTokens {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] secret;

    HmacOfferTokens(@Value("${openhire.offer.token-secret}") String secret) {
        if (secret == null || secret.length() < 16)
            throw new IllegalArgumentException("Offer token secret is too short");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String issue(String tenantId, String offerId, Instant expiresAt) {
        var payload = String.join("|", tenantId, offerId, Long.toString(expiresAt.getEpochSecond()));
        var body = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(mac(body));
    }

    @Override
    public TokenClaims verify(String token, Instant now) {
        if (token == null || token.length() > 1024) throw new IllegalArgumentException("Invalid offer link");
        int dot = token.indexOf('.');
        if (dot < 1) throw new IllegalArgumentException("Invalid offer link");
        var body = token.substring(0, dot);
        try {
            if (!MessageDigest.isEqual(mac(body), DECODER.decode(token.substring(dot + 1))))
                throw new IllegalArgumentException("Invalid offer link");
            var parts = new String(DECODER.decode(body), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 3) throw new IllegalArgumentException("Invalid offer link");
            var expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
            if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("This offer link has expired");
            return new TokenClaims(parts[0], parts[1], expiresAt);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(
                    malformed.getMessage() != null && malformed.getMessage().contains("expired")
                            ? malformed.getMessage()
                            : "Invalid offer link");
        }
    }

    private byte[] mac(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
