package com.openhire.identity.adapter.in.web;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.identity.application.IdentityApplicationService;
import com.openhire.platform.tenant.TenantContextFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        value = IdentityController.class,
        excludeAutoConfiguration = OAuth2ResourceServerAutoConfiguration.class,
        properties = {
            "openhire.security.mode=local",
            "openhire.internal.secret=test-internal-secret",
            "openhire.tenant.default-id="
        })
@Import({TenantContextFilter.class, IdentityControllerTest.MethodSecurityTestConfiguration.class})
@ImportAutoConfiguration({
    SecurityAutoConfiguration.class,
    ServletWebSecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class
})
class IdentityControllerTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    IdentityApplicationService service;

    @Test
    void tenantScopedEndpointRejectsMissingTenant() throws Exception {
        mvc.perform(get("/api/v1/roles").with(user("admin").roles("ORG_ADMIN"))).andExpect(status().isBadRequest());
    }

    @Test
    void tenantScopedEndpointUsesExplicitTenant() throws Exception {
        when(service.roles("tenant-a")).thenReturn(List.of());
        mvc.perform(get("/api/v1/roles").with(user("admin").roles("ORG_ADMIN")).header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isOk());
    }

    @Test
    void recruiterCanListTeamMembersButCannotManageRoles() throws Exception {
        when(service.members("tenant-a")).thenReturn(List.of());
        mvc.perform(get("/api/v1/users")
                        .with(user("recruiter").roles("RECRUITER"))
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/roles")
                        .with(user("recruiter").roles("RECRUITER"))
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizationAdminCanManageTenantRolesButNotPlatformOrganizations() throws Exception {
        when(service.roles("tenant-a")).thenReturn(List.of());
        mvc.perform(get("/api/v1/roles").with(user("admin").roles("ORG_ADMIN")).header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/platform/organizations").with(user("admin").roles("ORG_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanListPlatformOrganizations() throws Exception {
        when(service.organizations()).thenReturn(List.of());
        mvc.perform(get("/api/v1/platform/organizations")
                        .with(user("platform-admin").roles("SUPER_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void verifiedCodeReturnsAutomaticRecruiterRedirect() throws Exception {
        mvc.perform(post("/api/v1/public/verifications/verified-code/complete").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.redirectTo").value("/recruiter/today"));
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {
        @Bean
        SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/public/**")
                            .permitAll()
                            .anyRequest()
                            .authenticated())
                    .build();
        }
    }
}
