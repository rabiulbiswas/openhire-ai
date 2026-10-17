package com.openhire.integration.adapter.in.web;

import static org.junit.jupiter.api.Assertions.*;

import com.openhire.support.Access;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class McpAccessTest {
    final McpAccess access =
            new McpAccess(new Access("oidc", "demo-org", "test-secret"), "openhire-integrations", "openhire-web");

    JwtAuthenticationToken token(String audience, String scopes, String client) {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("test")
                        .header("alg", "RS256")
                        .subject("admin")
                        .audience(List.of(audience))
                        .claim("organization_id", "t")
                        .claim("scope", scopes)
                        .claim("azp", client)
                        .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN")));
    }

    @Test
    void requiresAudienceAndActualTokenScope() {
        assertEquals(
                "t",
                access.require(token("openhire-integrations", "integrations.read", "mcp-client"), "integrations.read"));
        assertThrows(
                ResponseStatusException.class,
                () -> access.require(token("other", "integrations.read", "mcp-client"), "integrations.read"));
        assertThrows(
                ResponseStatusException.class,
                () -> access.require(
                        token("openhire-integrations", "integrations.read", "mcp-client"), "integrations.write"));
    }

    @Test
    void mcpClientCannotApproveItsOwnWrites() {
        assertThrows(
                ResponseStatusException.class,
                () -> access.browser(
                        token("openhire-integrations", "integrations.read integrations.write", "mcp-client")));
        assertEquals("t", access.browser(token("web", "openid", "openhire-web")));
    }

    @Test
    void anonymousAndLocalModeCannotBypassMcpAuthentication() {
        assertThrows(ResponseStatusException.class, () -> access.require(null, "integrations.read"));
    }
}
