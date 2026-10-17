package com.openhire.identity.domain;

import static com.openhire.identity.domain.IdentityModels.InvitationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityPolicyTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void normalizesEmailWithoutChangingIdentity() {
        assertThat(IdentityPolicy.normalizeEmail(" Staff@OpenHire.AI ")).isEqualTo("staff@openhire.ai");
    }

    @Test
    void rejectsMalformedTenantSlug() {
        assertThatThrownBy(() -> IdentityPolicy.validateSlug("Open Hire")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpiredInvitation() {
        var invitation = new IdentityModels.Invitation(
                "i1",
                "o1",
                "a@b.com",
                Set.of("RECRUITER"),
                "hash",
                PENDING,
                clock.instant().minusSeconds(1),
                clock.instant().minusSeconds(100));
        assertThatThrownBy(() -> IdentityPolicy.requireAcceptable(invitation, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid or expired");
    }
}
