package com.openhire.identity.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.identity.service.ScimUsers;
import com.openhire.identity.service.port.IdentityRepositories.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class ScimControllerTest {
    @Test
    void noAnonymousOrWrongAudienceProvisioning() {
        var controller =
                new ScimController(mock(ScimUsers.class), mock(TenantSettings.class), mock(Organizations.class));
        assertThrows(ResponseStatusException.class, () -> controller.tenant(null));
        var token = new JwtAuthenticationToken(Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject("service")
                .claim("organization_id", "other")
                .claim("scope", "scim.provision")
                .audience(List.of("wrong"))
                .build());
        assertThrows(ResponseStatusException.class, () -> controller.tenant(token));
    }

    @Test
    void roleAndPasswordInjectionAreRejected() {
        var input = new HashMap<String, Object>(Map.of(
                "userName", "staff@example.com", "displayName", "Staff", "externalId", "staff-1", "active", true));
        assertEquals("staff@example.com", ScimController.input(input).userName());
        input.put("roles", List.of("SUPER_ADMIN"));
        assertThrows(ResponseStatusException.class, () -> ScimController.input(input));
        assertThrows(
                ResponseStatusException.class, () -> ScimController.patchValue(input, "roles", List.of("ORG_ADMIN")));
        assertThrows(ResponseStatusException.class, () -> ScimController.patchValue(input, "active", "true"));
    }

    @Test
    void samlAliasesAreStableAndTenantSpecific() {
        assertEquals(SamlController.alias("tenant"), SamlController.alias("tenant"));
        assertNotEquals(SamlController.alias("tenant"), SamlController.alias("other"));
        assertTrue(SamlController.alias("../../untrusted").matches("oh-saml-[a-f0-9-]+"));
    }
}
