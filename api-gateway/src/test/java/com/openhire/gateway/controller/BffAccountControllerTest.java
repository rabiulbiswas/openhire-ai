package com.openhire.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.server.MockWebSession;
import reactor.core.publisher.Mono;

class BffAccountControllerTest {
    private java.security.Principal principal() {
        var user = new org.springframework.security.oauth2.core.user.DefaultOAuth2User(
                java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_RECRUITER")),
                java.util.Map.of("sub", "user-9", "name", "Old login name", "tenant_id", "tenant-a"),
                "sub");
        return new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                user, user.getAuthorities(), "openhire-tenant");
    }

    @Test
    void centralNameWinsOverStaleLoginAndSessionNames() {
        var controller = new BffAccountController(null);
        var client = org.mockito.Mockito.mock(com.openhire.gateway.security.MemberProfileClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "profiles", client);
        var user = principal();
        org.mockito.Mockito.when(client.name(user)).thenReturn(Mono.just("Current durable name"));
        var session = new MockWebSession();
        session.getAttributes().put("openhire.profile.name", "Stale browser name");
        assertThat(controller.profile(Mono.just(user), session).block().name()).isEqualTo("Current durable name");
        var bff = new BffSessionController(
                "oidc",
                "",
                java.net.URI.create("http://localhost/login"),
                java.net.URI.create("http://localhost/login"));
        org.springframework.test.util.ReflectionTestUtils.setField(bff, "profiles", client);
        var response = bff.session(Mono.just(user), session).block();
        assertThat(((java.util.Map<?, ?>) response.get("user")).get("name")).isEqualTo("Current durable name");
    }

    @Test
    void failedCentralWriteNeverCreatesASessionOnlyRename() {
        var controller = new BffAccountController(null);
        var client = org.mockito.Mockito.mock(com.openhire.gateway.security.MemberProfileClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "profiles", client);
        var user = principal();
        org.mockito.Mockito.when(client.update(user, "New name"))
                .thenReturn(Mono.error(new IllegalStateException("identity unavailable")));
        var session = new MockWebSession();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller
                        .updateProfile(
                                new BffAccountController.MeProfile("New name", "Recruiter", "Asia/Kolkata", "English"),
                                session,
                                Mono.just(user))
                        .block())
                .isInstanceOf(IllegalStateException.class);
        assertThat(session.getAttributes()).doesNotContainKey("openhire.profile.name");
    }

    @Test
    void localProfileCanBeReadAndUpdatedInTheRedisBackedSessionContract() {
        var controller = new BffAccountController(null);
        var session = new MockWebSession();

        var initial = controller.profile(Mono.empty(), session).block();
        assertThat(initial).isNotNull();
        assertThat(initial.name()).isEqualTo("Local administrator");

        controller
                .updateProfile(
                        new BffAccountController.MeProfile("Rabiul Biswas", "Recruiter", "Asia/Kolkata", "English"),
                        session,
                        Mono.empty())
                .block();

        var updated = controller.profile(Mono.empty(), session).block();
        assertThat(updated).isNotNull();
        assertThat(updated.name()).isEqualTo("Rabiul Biswas");
        assertThat(updated.title()).isEqualTo("Recruiter");
    }

    @Test
    void unauthenticatedLocalModeStillReportsOnlyTheCurrentBrowserSession() {
        var controller = new BffAccountController(null);
        var session = new MockWebSession();

        var sessions = controller
                .browserSessions(
                        Mono.empty(),
                        session,
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit Chrome/140 Safari/537")
                .block();

        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst().current()).isTrue();
        assertThat(sessions.getFirst().device()).isEqualTo("Chrome on macOS");
    }
}
