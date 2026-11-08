package com.openhire.interview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.interview.domain.*;
import com.openhire.interview.domain.InterviewTypes.*;
import com.openhire.interview.service.port.InterviewPorts.LoopRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterviewAssistantTest {
    final LoopRepository loops = mock(LoopRepository.class);
    final InterviewAssistant.Gateway gateway = mock(InterviewAssistant.Gateway.class);
    final Instant now = Instant.parse("2026-10-27T10:00:00Z");
    final Viewer panel = new Viewer("panel", "panel@example.test", "Panel", Set.of("INTERVIEWER"));
    final Viewer outsider = new Viewer("other", "other@example.test", "Other", Set.of("INTERVIEWER"));
    final Viewer recruiter = new Viewer("recruiter", "r@example.test", "Recruiter", Set.of("RECRUITER"));
    final InterviewAssistant assistant = new InterviewAssistant(loops, gateway, Clock.fixed(now, ZoneOffset.UTC));
    InterviewLoop loop;
    String round;

    @BeforeEach
    void setup() {
        loop = InterviewLoop.create(
                "tenant",
                "app",
                "candidate",
                "Ada",
                "job",
                "Engineer",
                "recruiter",
                List.of(new RoundPlan("Technical", 60, List.of())),
                List.of(new Competency("c1", "Reliability")),
                now.minusSeconds(7200));
        round = loop.rounds().getFirst().id();
        loop.schedule(
                round,
                new Slot(now.minusSeconds(3600), now, "UTC"),
                List.of(new PanelMember("Panel", "panel@example.test", null)),
                null,
                Duration.ofHours(24),
                now.minusSeconds(7200));
        when(loops.findByRound("tenant", round)).thenReturn(Optional.of(loop));
        when(loops.findByApplication("tenant", "app")).thenReturn(Optional.of(loop));
        when(gateway.generate(anyString(), any(), any(), anyString(), anyList()))
                .thenReturn(Map.of("questions", List.of(), "statements", List.of()));
    }

    @Test
    void onlyAssignedInterviewerCanGenerateOwnFeedback() {
        assistant.feedback("tenant", round, panel, "Explained retry-safe writes.");
        verify(gateway)
                .generate(
                        eq("tenant"),
                        eq(loop),
                        any(),
                        eq("FEEDBACK"),
                        eq(List.of(Map.of("sourceId", "own-notes", "text", "Explained retry-safe writes."))));
        assertThatThrownBy(() -> assistant.feedback("tenant", round, recruiter, "Some notes"))
                .isInstanceOf(InterviewApplicationService.AccessDenied.class);
        assertThatThrownBy(() -> assistant.feedback("tenant", round, outsider, "Some notes"))
                .isInstanceOf(InterviewApplicationService.AccessDenied.class);
    }

    @Test
    void tenantIsolationAndBlindFeedbackApplyToAiEndpoints() {
        assertThatThrownBy(() -> assistant.questions("other", round, panel))
                .isInstanceOf(com.openhire.platform.web.ResourceNotFoundException.class);
        assertThatThrownBy(() -> assistant.questions("tenant", round, outsider))
                .isInstanceOf(InterviewApplicationService.AccessDenied.class);
        assertThatThrownBy(() -> assistant.debrief("tenant", "app", panel))
                .isInstanceOf(InterviewApplicationService.AccessDenied.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void cannotDraftAfterSubmissionOrBeforeTheInterviewStarts() {
        var future = new InterviewAssistant(loops, gateway, Clock.fixed(now.minusSeconds(7200), ZoneOffset.UTC));
        assertThatThrownBy(() -> future.feedback("tenant", round, panel, "Some notes"))
                .isInstanceOf(IllegalStateException.class);
        loop.submitScorecard(round, panel, List.of(new RatingEntry("c1", Rating.GOOD)), "Explained retries", null, now);
        assertThatThrownBy(() -> assistant.feedback("tenant", round, panel, "Some notes"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(assistant.kit("tenant", round, panel)).containsEntry("canSubmit", false);
    }

    @Test
    void debriefUsesSubmittedEvidenceAndDoesNotSaveAnAiDecision() {
        loop.submitScorecard(round, panel, List.of(new RatingEntry("c1", Rating.GOOD)), "Explained retries", null, now);
        assistant.debrief("tenant", "app", panel);
        verify(gateway)
                .generate(
                        eq("tenant"),
                        eq(loop),
                        any(),
                        eq("DEBRIEF"),
                        eq(List.of(Map.of("sourceId", "R1-1", "text", "Explained retries"))));
        verify(loops, never()).save(any());
    }

    @Test
    void matchingDisplayNameDoesNotGrantAccessToAnEmailAssignedPanel() {
        var impostor = new Viewer("other", null, "Panel", Set.of("INTERVIEWER"));
        assertThatThrownBy(() -> assistant.feedback("tenant", round, impostor, "Some notes"))
                .isInstanceOf(InterviewApplicationService.AccessDenied.class);
    }

    @Test
    void rejectsEmptyOrOversizedEvidence() {
        assertThatThrownBy(() -> assistant.feedback("tenant", round, panel, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assistant.feedback("tenant", round, panel, "x".repeat(6001)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gateway);
    }
}
