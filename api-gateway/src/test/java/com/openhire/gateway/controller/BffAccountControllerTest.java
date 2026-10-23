package com.openhire.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.server.MockWebSession;
import reactor.core.publisher.Mono;

class BffAccountControllerTest {
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
                        session)
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
