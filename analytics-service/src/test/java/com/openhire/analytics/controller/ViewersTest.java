package com.openhire.analytics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.support.Access;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class ViewersTest {
    private final Viewers viewers = new Viewers(new Access("oidc", "demo-org", "secret"), "oidc");

    private static JwtAuthenticationToken token(String organization, String... roles) {
        var jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("user-1")
                .claim("name", "Asha Admin")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (organization != null) jwt.claim("organization_id", organization);
        return new JwtAuthenticationToken(
                jwt.build(),
                Arrays.stream(roles)
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    @Test
    void anyRecruitingStaffRoleReadsAnalyticsForItsOwnTenantOnly() {
        for (var role : new String[] {"RECRUITER", "HR_ADMIN", "HIRING_MANAGER", "ORG_ADMIN", "SUPER_ADMIN"})
            assertThat(viewers.analyticsTenant(token("t1", role))).isEqualTo("t1");
        assertThatThrownBy(() -> viewers.analyticsTenant(token("t1", "EMPLOYEE")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> viewers.analyticsTenant(token("t1", "COMPLIANCE_OFFICER")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> viewers.analyticsTenant(token(null, "RECRUITER")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void serviceHealthAndConsumersAreForSuperAdminsOnly() {
        viewers.requireSuperAdmin(token(null, "SUPER_ADMIN"));
        assertThatThrownBy(() -> viewers.requireSuperAdmin(token("t1", "ORG_ADMIN")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sloIsForSuperAndOrgAdminsAndOrgAdminsAreScopedToTheirTenant() {
        assertThat(viewers.sloTenant(token(null, "SUPER_ADMIN"))).isNull();
        assertThat(viewers.sloTenant(token("t1", "ORG_ADMIN"))).isEqualTo("t1");
        assertThatThrownBy(() -> viewers.sloTenant(token("t1", "RECRUITER")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deadLetterOperatorsAreOrgAdminsOrSuperAdmins() {
        var org = viewers.operator(token("t1", "ORG_ADMIN"));
        assertThat(org.tenant()).isEqualTo("t1");
        assertThat(org.superAdmin()).isFalse();
        assertThat(org.id()).isEqualTo("user-1");
        assertThat(org.name()).isEqualTo("Asha Admin");
        var root = viewers.operator(token(null, "SUPER_ADMIN"));
        assertThat(root.superAdmin()).isTrue();
        assertThat(root.tenant()).isEmpty();
        assertThatThrownBy(() -> viewers.operator(token("t1", "HR_ADMIN"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> viewers.operator(token(null, "ORG_ADMIN")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
