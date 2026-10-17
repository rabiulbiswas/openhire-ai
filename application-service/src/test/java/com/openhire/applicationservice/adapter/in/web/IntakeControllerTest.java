package com.openhire.applicationservice.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.application.IntakeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class IntakeControllerTest {
    @Test
    void tenantIsTakenFromVerifiedJwtAndNotCallerHeaderOrLocalDefault() {
        var service = mock(IntakeService.class);
        var controller = new IntakeController(service, "oidc", "untrusted-default");
        var jwt = Jwt.withTokenValue("verified-token")
                .header("alg", "RS256")
                .subject("recruiter")
                .claim("organization_id", "trusted-org")
                .build();
        when(service.list("trusted-org", null, 0, 50)).thenReturn(List.of());
        controller.list(new JwtAuthenticationToken(jwt), null, 0, 50);
        verify(service).list("trusted-org", null, 0, 50);
    }

    @Test
    void productionRequestWithoutTenantClaimCannotUseDevelopmentTenant() {
        var service = mock(IntakeService.class);
        var controller = new IntakeController(service, "oidc", "demo-org");
        var jwt = Jwt.withTokenValue("verified-token")
                .header("alg", "RS256")
                .subject("recruiter")
                .build();
        assertThatThrownBy(() -> controller.list(new JwtAuthenticationToken(jwt), null, 0, 50))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
