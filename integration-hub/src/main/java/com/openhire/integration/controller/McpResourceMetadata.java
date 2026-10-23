package com.openhire.integration.controller;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class McpResourceMetadata {
    private final String baseUrl;
    private final String issuer;

    McpResourceMetadata(
            @Value("${openhire.integration.public-base-url:http://localhost:8080}") String baseUrl,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.issuer = issuer;
    }

    @GetMapping("/api/v1/public/mcp/resource-metadata")
    Map<String, Object> metadata() {
        return Map.of(
                "resource",
                baseUrl + "/api/v1/mcp",
                "authorization_servers",
                List.of(issuer),
                "scopes_supported",
                List.of("integrations.read", "integrations.write"),
                "bearer_methods_supported",
                List.of("header"));
    }
}
