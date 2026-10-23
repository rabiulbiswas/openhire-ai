package com.openhire.integration.controller;

import com.openhire.support.Access;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class McpAccess {
    private final Access access;
    private final String audience;
    private final String browserClient;

    McpAccess(
            Access access,
            @Value("${openhire.integration.mcp-audience:openhire-integrations}") String audience,
            @Value("${openhire.integration.browser-client:openhire-web}") String browserClient) {
        this.access = access;
        this.audience = audience;
        this.browserClient = browserClient;
    }

    String require(Authentication auth, String scope) {
        String tenant = admin(auth);
        if (!(auth instanceof JwtAuthenticationToken jwt)
                || !jwt.getToken().getAudience().contains(audience)) deny();
        var jwt = (JwtAuthenticationToken) auth;
        String scopes = jwt.getToken().getClaimAsString("scope");
        if (scopes == null || !Arrays.asList(scopes.split(" +")).contains(scope)) deny();
        return tenant;
    }

    String browser(Authentication auth) {
        String tenant = admin(auth);
        if (!(auth instanceof JwtAuthenticationToken jwt)
                || !browserClient.equals(jwt.getToken().getClaimAsString("azp"))) deny();
        return tenant;
    }

    private String admin(Authentication auth) {
        if (!access.role(auth, "ORG_ADMIN") && !access.role(auth, "SUPER_ADMIN")) deny();
        return access.tenant(auth);
    }

    private static void deny() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Required MCP audience, role or scope is missing");
    }
}
