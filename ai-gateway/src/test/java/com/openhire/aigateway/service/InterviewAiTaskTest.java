package com.openhire.aigateway.service;

import static org.assertj.core.api.Assertions.*;

import com.openhire.aigateway.Fixtures.Rig;
import com.openhire.aigateway.domain.AiTypes.GuardRejection;
import com.openhire.aigateway.domain.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class InterviewAiTaskTest {
    @Test
    void generatesGovernedQuestionsWithoutRatingsOrDecisions() {
        var rig = new Rig();
        rig.local.reply = request -> Json.write(Map.of(
                "questions",
                List.of(Map.of("question", "How do you make retries safe?", "followUp", "Discuss idempotency.")),
                "statements",
                List.of()));
        var result = new InterviewAiTask(rig.gateway)
                .generate(
                        "tenant",
                        new InterviewAiTask.Request(
                                "QUESTIONS", "Technical round; reliability", List.of(), "Ada Example"));
        assertThat(result)
                .containsKeys("questions", "statements", "provenance")
                .doesNotContainKeys("rating", "decision");
    }

    @Test
    void feedbackRequiresExactQuotesFromTheSpecifiedSource() {
        var sources = Map.of("own-notes", "Explained safe retries and idempotency keys.");
        var valid = Map.<String, Object>of(
                "questions",
                List.of(),
                "statements",
                List.of(Map.of(
                        "text", "Explained idempotency.", "sourceId", "own-notes", "quote", "idempotency keys")));
        assertThat(InterviewAiTask.guard(valid, "FEEDBACK", sources)).containsKey("statements");
        var fabricated = Map.<String, Object>of(
                "questions",
                List.of(),
                "statements",
                List.of(Map.of(
                        "text", "Excellent leadership.", "sourceId", "another-panel", "quote", "invented evidence")));
        assertThatThrownBy(() -> InterviewAiTask.guard(fabricated, "FEEDBACK", sources))
                .isInstanceOf(GuardRejection.class);
    }

    @Test
    void refusesProtectedQuestionsAndFeedbackWithoutEvidence() {
        var bad = Map.<String, Object>of(
                "questions",
                List.of(Map.of("question", "What is your marital status?", "followUp", "")),
                "statements",
                List.of());
        assertThatThrownBy(() -> InterviewAiTask.guard(bad, "QUESTIONS", Map.of()))
                .isInstanceOf(GuardRejection.class);
        var task = new InterviewAiTask(new Rig().gateway);
        assertThatThrownBy(
                        () -> task.generate("t", new InterviewAiTask.Request("FEEDBACK", "Technical", List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> task.generate("t", new InterviewAiTask.Request("DECIDE", "Technical", List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
