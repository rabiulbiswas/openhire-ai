package com.openhire.jobservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class TenantOriginTest {
    private static final class Mappings extends CorsRegistry {
        CorsConfiguration policy() {
            return getCorsConfigurations().get("/api/**");
        }
    }

    private MockHttpServletResponse check(String host, int port, String origin) throws Exception {
        var mappings = new Mappings();
        new WebConfiguration().addCorsMappings(mappings);
        var request = new MockHttpServletRequest("POST", "/api/v1/jobs");
        request.setScheme("http");
        request.setServerName(host);
        request.setServerPort(port);
        request.addHeader("Host", host + (port == 80 ? "" : ":" + port));
        request.addHeader("Origin", origin);
        var response = new MockHttpServletResponse();
        new DefaultCorsProcessor().processRequest(mappings.policy(), request, response);
        return response;
    }

    @Test
    void rewrittenInternalHostReproducesTheTenantPostCorsRejection() throws Exception {
        var response = check("job-service", 8082, "http://codelogicx.localhost");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Invalid CORS request");
    }

    @Test
    void preservingThePublicHostAllowsSameOriginTenantPostsWithoutWildcardCors() throws Exception {
        for (String host : new String[] {"codelogicx.localhost", "another-tenant.localhost"}) {
            assertThat(check(host, 80, "http://" + host).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void unrelatedBrowserOriginsRemainRejected() throws Exception {
        assertThat(check("codelogicx.localhost", 80, "https://untrusted.example")
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void existingExplicitLocalDevelopmentOriginsRemainSupported() throws Exception {
        assertThat(check("job-service", 8082, "http://localhost:5173").getStatus())
                .isEqualTo(200);
    }
}
