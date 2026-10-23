package com.openhire.gateway.controller;

import com.openhire.gateway.config.OpenApiPortalConfiguration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiPortalController {
    private final OpenApiPortalConfiguration configuration;

    public OpenApiPortalController(OpenApiPortalConfiguration configuration) {
        this.configuration = configuration;
    }

    @GetMapping("/openapi/config")
    public Map<String, Object> swaggerConfig() {
        return configuration.swaggerConfig();
    }
}
