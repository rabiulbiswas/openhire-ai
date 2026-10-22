package com.openhire.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiPortalConfigurationTest {
    @Test
    void catalogueHasAllServicesAndDisablesExecutionAndExternalValidation() {
        var config = new OpenApiPortalConfiguration().swaggerConfig();
        @SuppressWarnings("unchecked")
        var urls = (List<Map<String, String>>) config.get("urls");
        assertThat(urls).hasSize(18);
        assertThat(urls.stream().map(url -> url.get("name")).distinct()).hasSize(18);
        assertThat(urls).contains(Map.of("name", "api-gateway", "url", "/v3/api-docs"));
        assertThat(urls).allSatisfy(url -> assertThat(url.get("url")).startsWith("/"));
        assertThat((List<?>) config.get("supportedSubmitMethods")).isEmpty();
        assertThat(config)
                .containsEntry("tryItOutEnabled", false)
                .containsEntry("queryConfigEnabled", false)
                .containsEntry("persistAuthorization", false)
                .containsEntry("validatorUrl", "");
    }
}
