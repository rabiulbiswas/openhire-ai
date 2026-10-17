package com.openhire.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

class IdentitySecurityContextTest {
    @Configuration
    @EnableWebSecurity
    static class Security {
        @Bean
        JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(JwtDecoder.class);
        }
    }

    @Test
    void internalCredentialsDoNotRequireBrowserCsrfButProtectedRoutesStillRequireAuthentication() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "openhire.security.mode=oidc");
            context.register(Security.class, SecurityConfiguration.class);
            context.refresh();
            var filter = context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class);
            for (String path : new String[] {"/api/v1/internal/api-keys/validate", "/api/v1/api-keys"}) {
                var request = new MockHttpServletRequest("POST", path);
                request.setServletPath(path);
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res)
                        .setStatus(204));
                assertThat(response.getStatus()).isEqualTo(path.contains("/internal/") ? 204 : 401);
            }
        }
    }
}
