package com.openhire.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.interview.domain.InterviewTypes.PanelMember;
import com.openhire.interview.domain.InterviewTypes.Rating;
import com.openhire.interview.domain.InterviewTypes.RatingEntry;
import com.openhire.interview.domain.InterviewTypes.Viewer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DebriefBuilderTest {
    @Test
    void groupsRatingsIntoStrengthsConcernsAndDisagreementsWithRoundAttribution() {
        var loop = InterviewLoopTest.loop();
        var technical = loop.rounds().get(0).id();
        var design = loop.rounds().get(1).id();
        var past = InterviewLoopTest.NOW.minus(Duration.ofHours(4));
        var carl = new PanelMember("Carl Wu", "carl@example.test", "LEAD");
        var carlView = new Viewer("sub-c", "carl@example.test", "Carl Wu", Set.of("INTERVIEWER"));
        loop.schedule(
                technical,
                InterviewLoopTest.slot(past, 60),
                List.of(InterviewLoopTest.ASHA),
                null,
                Duration.ofHours(24),
                past.minus(Duration.ofDays(1)));
        loop.schedule(
                design,
                InterviewLoopTest.slot(past.plus(Duration.ofHours(2)), 90),
                List.of(carl),
                null,
                Duration.ofHours(24),
                past.minus(Duration.ofDays(1)));

        loop.submitScorecard(
                technical,
                InterviewLoopTest.ASHA_VIEW,
                List.of(new RatingEntry("c1", Rating.EXCELLENT), new RatingEntry("c2", Rating.GOOD)),
                "Strong",
                null,
                InterviewLoopTest.NOW);
        loop.submitScorecard(
                design,
                carlView,
                List.of(new RatingEntry("c1", Rating.MIXED), new RatingEntry("c2", Rating.EXCELLENT)),
                "Mixed",
                null,
                InterviewLoopTest.NOW);

        var debrief = DebriefBuilder.build(loop);

        assertThat(debrief.generatedBy()).isEqualTo("RULES");
        assertThat(debrief.roundsCompleted()).isEqualTo(2);
        assertThat(debrief.roundsTotal()).isEqualTo(2);
        assertThat(debrief.consistentStrengths()).singleElement().satisfies(point -> {
            assertThat(point.text()).startsWith("Communication");
            assertThat(point.fromRounds()).containsExactly("Technical", "System design");
        });
        assertThat(debrief.disagreements()).singleElement().satisfies(point -> assertThat(point.text())
                .contains("Problem solving")
                .contains("mixed")
                .contains("excellent"));
        assertThat(debrief.concerns()).singleElement().satisfies(point -> assertThat(point.fromRounds())
                .containsExactly("System design"));
    }

    @Test
    void anEmptyLoopHasNothingToSummarise() {
        var debrief = DebriefBuilder.build(InterviewLoopTest.loop());

        assertThat(debrief.roundsCompleted()).isZero();
        assertThat(debrief.consistentStrengths()).isEmpty();
        assertThat(debrief.concerns()).isEmpty();
        assertThat(debrief.disagreements()).isEmpty();
    }
}
