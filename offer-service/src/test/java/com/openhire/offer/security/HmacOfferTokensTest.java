package com.openhire.offer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HmacOfferTokensTest {
    private final Instant now = Instant.parse("2026-10-15T05:00:00Z");
    private final HmacOfferTokens tokens = new HmacOfferTokens("a-long-enough-test-secret");

    @Test
    void aTokenRoundTripsItsClaims() {
        var token = tokens.issue("t1", "offer_1", now.plusSeconds(60));

        var claims = tokens.verify(token, now);

        assertThat(claims.tenantId()).isEqualTo("t1");
        assertThat(claims.offerId()).isEqualTo("offer_1");
        assertThat(claims.expiresAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void anExpiredTokenIsRefusedWithAClearMessage() {
        var token = tokens.issue("t1", "offer_1", now.minusSeconds(1));
        assertThatThrownBy(() -> tokens.verify(token, now)).hasMessageContaining("expired");
    }

    @Test
    void tamperingWithTheBodyOrTheSignatureIsRefused() {
        var token = tokens.issue("t1", "offer_1", now.plusSeconds(60));
        var other = tokens.issue("t2", "offer_9", now.plusSeconds(60));
        var forged = other.substring(0, other.indexOf('.')) + token.substring(token.indexOf('.'));

        assertThatThrownBy(() -> tokens.verify(forged, now)).hasMessage("Invalid offer link");
        assertThatThrownBy(() -> tokens.verify(token.substring(0, token.length() - 3) + "AAA", now))
                .hasMessage("Invalid offer link");
        assertThatThrownBy(() -> tokens.verify("not-a-token", now)).hasMessage("Invalid offer link");
        assertThatThrownBy(() -> tokens.verify(null, now)).hasMessage("Invalid offer link");
        assertThatThrownBy(() -> tokens.verify("x".repeat(2000), now)).hasMessage("Invalid offer link");
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRefused() {
        var token = new HmacOfferTokens("a-different-long-enough-secret").issue("t1", "offer_1", now.plusSeconds(60));
        assertThatThrownBy(() -> tokens.verify(token, now)).hasMessage("Invalid offer link");
    }

    @Test
    void aShortSecretIsRejectedAtStartup() {
        assertThatThrownBy(() -> new HmacOfferTokens("short")).hasMessageContaining("too short");
    }
}
