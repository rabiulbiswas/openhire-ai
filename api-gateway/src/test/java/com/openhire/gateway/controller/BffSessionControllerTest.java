package com.openhire.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.server.MockWebSession;
import reactor.core.publisher.Mono;

class BffSessionControllerTest {
    @Test
    void recruitersTrackRequestsButCannotDecideAndApproverRolesReceiveDecisionPermissions() {
        var controller = new BffSessionController(
                "oidc", "tenant-a", URI.create("http://localhost/login"), URI.create("http://localhost/candidate"));
        java.util.Set<String> recruiter = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                controller, "rolePermissions", "recruiter");
        assertThat(recruiter).contains("approval:read").doesNotContain("approval:decide", "approval:delegate");
        for (var role : java.util.List.of("hiring_manager", "hr_admin", "org_admin")) {
            java.util.Set<String> approver =
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "rolePermissions", role);
            assertThat(approver).contains("approval:read", "approval:decide");
        }
    }

    @Test
    void organisationAdminsReceiveCompliancePermissionsAndRetiredRoleReceivesNone() {
        var controller = new BffSessionController(
                "oidc", "tenant-a", URI.create("http://localhost/login"), URI.create("http://localhost/candidate"));
        java.util.Set<String> permissions = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                controller, "rolePermissions", "org_admin");
        assertThat(permissions)
                .contains(
                        "compliance:audit:read",
                        "compliance:fairness:read",
                        "compliance:consent:manage",
                        "compliance:retention:manage",
                        "privacy:execute",
                        "candidate:export");
        java.util.Set<String> retired = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                controller, "rolePermissions", "compliance");
        assertThat(retired).isEmpty();
    }

    @Test
    void csrfEndpointReadsTheSecurityRequestAttribute() {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(
                CsrfTestConfiguration.class);
        var token = new org.springframework.security.web.server.csrf.DefaultCsrfToken(
                "X-XSRF-TOKEN", "_csrf", "fixture-token");
        org.springframework.test.web.reactive.server.WebTestClient.bindToApplicationContext(context)
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes()
                            .put(
                                    org.springframework.security.web.server.csrf.CsrfToken.class.getName(),
                                    Mono.just(token));
                    return chain.filter(exchange);
                })
                .build()
                .get()
                .uri("/bff/csrf")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.token")
                .isEqualTo("fixture-token");
        context.close();
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.web.reactive.config.EnableWebFlux
    static class CsrfTestConfiguration {
        @org.springframework.context.annotation.Bean
        BffSessionController controller() {
            return new BffSessionController(
                    "oidc",
                    "test-tenant",
                    URI.create("http://localhost/login"),
                    URI.create("http://localhost/candidate"));
        }

        @org.springframework.context.annotation.Bean
        com.openhire.gateway.security.TenantPortalAccess portals() {
            return org.mockito.Mockito.mock(com.openhire.gateway.security.TenantPortalAccess.class);
        }

        @org.springframework.context.annotation.Bean
        com.openhire.gateway.security.MemberProfileClient profiles() {
            return org.mockito.Mockito.mock(com.openhire.gateway.security.MemberProfileClient.class);
        }
    }

    @Test
    void logoutWithoutAuthenticationInvalidatesSessionAndReturnsToSignIn() {
        var signIn = URI.create("http://localhost:5176/login");
        var controller = new BffSessionController(
                "oidc", "demo-org", signIn, URI.create("http://localhost:5176/candidate-sign-in"));
        var session = new MockWebSession();

        var response = controller.browserLogout(Mono.empty(), session).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isEqualTo(signIn);
        assertThat(session.isStarted()).isFalse();
    }
}
