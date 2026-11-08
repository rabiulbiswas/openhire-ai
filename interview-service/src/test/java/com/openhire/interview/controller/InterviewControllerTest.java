package com.openhire.interview.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.interview.domain.InterviewTypes.*;
import com.openhire.interview.dto.InterviewViews.*;
import com.openhire.interview.service.InterviewApplicationService;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class InterviewControllerTest {
    final InterviewApplicationService service = mock(InterviewApplicationService.class);
    final Viewers viewers = mock(Viewers.class);
    final InterviewController controller = new InterviewController(service, viewers);

    @Test
    void listPaginationIsAppliedOnTheServerAndInvalidLimitsAreRejected() {
        var participant = new Viewer("p", "p@example.test", "Panel", Set.of("INTERVIEWER"));
        when(viewers.participant(null)).thenReturn(participant);
        when(viewers.tenant(null)).thenReturn("tenant");
        var events = new ArrayList<InterviewEventView>();
        for (int i = 0; i < 5; i++)
            events.add(new InterviewEventView(
                    "r" + i,
                    "a",
                    "c",
                    "Ada",
                    "Engineer",
                    "Technical",
                    "2026-10-27T10:00:00Z",
                    60,
                    "Panel",
                    "SCHEDULED",
                    false));
        when(service.week("tenant", LocalDate.parse("2026-10-26"), participant)).thenReturn(Page.of(events));
        var page = (Map<?, ?>) controller.week(null, "2026-10-26", 2, 2);
        assertThat((List<?>) page.get("data")).hasSize(2);
        assertThat(page.get("total")).isEqualTo(5);
        assertThat(page.get("hasMore")).isEqualTo(true);
        assertThatThrownBy(() -> controller.week(null, "2026-10-26", 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.week(null, "2026-10-26", 1, 51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aiDraftCannotBeSubmittedWithoutExplicitReview() {
        var panel = new Viewer("p", "p@example.test", "Panel", Set.of("INTERVIEWER"));
        when(viewers.participant(null)).thenReturn(panel);
        when(viewers.tenant(null)).thenReturn("tenant");
        var ratings = List.of(new InterviewController.RatingInput("c", "GOOD"));
        assertThatThrownBy(() -> controller.submit(
                        null,
                        "round",
                        new InterviewController.ScorecardInput(ratings, "Original notes", null, "AI draft", false)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
        controller.submit(
                null,
                "round",
                new InterviewController.ScorecardInput(ratings, "Original notes", null, "AI draft", true));
        verify(service)
                .submitScorecard(
                        eq("tenant"),
                        eq("round"),
                        eq(panel),
                        anyList(),
                        contains("Original notes\n\nAI-assisted feedback (reviewed by interviewer):\nAI draft"),
                        isNull());
    }
}
