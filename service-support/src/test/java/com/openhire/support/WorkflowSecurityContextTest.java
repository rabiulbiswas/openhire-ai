package com.openhire.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

class WorkflowSecurityContextTest {
    @Configuration
    @EnableWebSecurity
    static class WebSecurity {
        @org.springframework.context.annotation.Bean
        org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.JwtDecoder.class);
        }
    }

    @Test
    void errorDispatchKeepsOriginalStatusInsteadOfRequiringAnotherLogin() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "openhire.security.mode=oidc");
            context.register(WebSecurity.class, WorkflowSecurity.class);
            context.refresh();
            var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/error");
            request.setServletPath("/error");
            request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)
                    .doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res)
                            .setStatus(403));
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test
    void securityConfigurationAndFilterChainHaveDistinctBeanNames() {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.setAllowBeanDefinitionOverriding(false);
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "openhire.security.mode=local");
            context.register(WebSecurity.class, WorkflowSecurity.class);
            context.refresh();
            assertThat(context.getBean("workflowSecurity")).isInstanceOf(WorkflowSecurity.class);
            assertThat(context.getBean("workflowFilterChain")).isInstanceOf(SecurityFilterChain.class);
        }
    }
}
