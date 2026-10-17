package com.openhire.interview.adapter.out.token;

import com.openhire.interview.application.port.InterviewPorts.SchedulingTokens;
import com.openhire.interview.application.port.InterviewPorts.TokenClaims;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Self-contained, tamper-evident booking link: base64url(tenant|round|expiry).base64url(HMAC-SHA256). */
@Component
class HmacSchedulingTokens implements SchedulingTokens {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] secret;

    HmacSchedulingTokens(@Value("${openhire.interview.token-secret}") String secret) {
        if (secret == null || secret.length() < 16)
            throw new IllegalArgumentException("Scheduling token secret is too short");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String issue(String tenantId, String roundId, Instant expiresAt) {
        var payload = String.join("|", tenantId, roundId, Long.toString(expiresAt.getEpochSecond()));
        var body = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(mac(body));
    }

    @Override
    public TokenClaims verify(String token, Instant now) {
        if (token == null || token.length() > 1024) throw new IllegalArgumentException("Invalid scheduling link");
        int dot = token.indexOf('.');
        if (dot < 1) throw new IllegalArgumentException("Invalid scheduling link");
        var body = token.substring(0, dot);
        byte[] supplied;
        try {
            supplied = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Invalid scheduling link");
        }
        if (!MessageDigest.isEqual(mac(body), supplied)) throw new IllegalArgumentException("Invalid scheduling link");
        var parts = new String(DECODER.decode(body), StandardCharsets.UTF_8).split("\\|");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid scheduling link");
        var expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("This scheduling link has expired");
        return new TokenClaims(parts[0], parts[1], expiresAt);
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
