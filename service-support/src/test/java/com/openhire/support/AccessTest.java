package com.openhire.support;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class AccessTest {
    @Test
    void missingSubjectCannotCollapseDifferentUsersIntoOneActor() {
        var token = new JwtAuthenticationToken(Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .claim("organization_id", "tenant")
                .build());
        assertThatThrownBy(() -> new Access("oidc", "demo-org", "secret").actor(token))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cannotFallBackToDevelopmentTenantInOidcMode() {
        var access = new Access("oidc", "demo-org", "test-secret");
        assertThatThrownBy(() -> access.tenant(null)).isInstanceOf(ResponseStatusException.class);
        var token = new JwtAuthenticationToken(Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject("user")
                .claim("organization_id", "verified-org")
                .build());
        assertThat(access.tenant(token)).isEqualTo("verified-org");
        assertThatThrownBy(() -> access.internal("wrong")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void lengthPrefixPreventsAmbiguousIdempotencyKeys() {
        assertThat(Records.hash("ab", "c")).isNotEqualTo(Records.hash("a", "bc"));
    }
}
