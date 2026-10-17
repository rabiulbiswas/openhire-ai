package com.openhire.interview.adapter.out.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HmacSchedulingTokensTest {
    private final HmacSchedulingTokens tokens = new HmacSchedulingTokens("a-test-secret-of-sufficient-length");
    private final Instant now = Instant.parse("2026-10-12T04:00:00Z");

    @Test
    void roundTripsTenantAndRound() {
        var token = tokens.issue("tenant-a", "loop_1_r1", now.plus(Duration.ofDays(7)));
        var claims = tokens.verify(token, now);

        assertThat(claims.tenantId()).isEqualTo("tenant-a");
        assertThat(claims.roundId()).isEqualTo("loop_1_r1");
    }

    @Test
    void rejectsTamperedForeignAndExpiredTokens() {
        var token = tokens.issue("tenant-a", "loop_1_r1", now.plus(Duration.ofDays(7)));
        var body = token.substring(0, token.indexOf('.'));
        var forged = (body.charAt(0) == 'A' ? 'B' : 'A') + token.substring(1);
        var other = new HmacSchedulingTokens("another-secret-of-sufficient-length")
                .issue("tenant-a", "loop_1_r1", now.plus(Duration.ofDays(1)));

        assertThatThrownBy(() -> tokens.verify(forged, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.verify(other, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.verify("not-a-token", now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.verify(token, now.plus(Duration.ofDays(8))))
                .hasMessageContaining("expired");
    }
}
