package com.openhire.aiworkers.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchingRequestTest {
    static Map<String, Object> payload() {
        var payload = new HashMap<String, Object>();
        payload.put("applicationId", "app-1");
        payload.put("candidateId", "cand-1");
        payload.put("candidateName", "Priya Das");
        payload.put("resumeId", "res-1");
        payload.put("jobId", "job-1");
        payload.put("jobTitle", "Engineer");
        payload.put("jobVersion", 3);
        payload.put(
                "rubric",
                List.of(
                        Map.of("id", "r1", "label", "Java", "weight", 30, "requirement", "MUST"),
                        Map.of("id", "r2", "label", "Payments", "weight", 10.5)));
        payload.put("mustHaveSkills", List.of("Java"));
        payload.put(
                "knockoutQuestions",
                List.of(Map.of("id", "permit", "question", "Work permit?", "requiredAnswer", "yes")));
        payload.put("answers", Map.of("permit", "no"));
        payload.put("eligibilityApproved", false);
        return payload;
    }

    static Map<String, Object> envelope(Map<String, Object> payload) {
        return Map.of("eventId", "evt-1", "eventType", "matching.requested.v1", "tenantId", "t1", "payload", payload);
    }

    @Test
    void parsesTheContractAndDefaultsTheRequirementToNice() {
        var request = MatchingRequest.fromEnvelope(envelope(payload()));

        assertThat(request.applicationId()).isEqualTo("app-1");
        assertThat(request.jobVersion()).isEqualTo(3);
        assertThat(request.rubric())
                .extracting(MatchingRequest.Criterion::requirement)
                .containsExactly("MUST", "NICE");
        assertThat(request.rubric().get(1).weight()).isEqualTo(10.5);
        assertThat(request.mustHaveSkills()).containsExactly("Java");
    }

    @Test
    void roundTripsThroughTheEnvelope() {
        var request = MatchingRequest.fromEnvelope(envelope(payload()));
        assertThat(MatchingRequest.fromEnvelope(request.toEnvelope())).isEqualTo(request);
    }

    @Test
    void knockoutsFailUnlessAnsweredAsRequiredOrApproved() {
        assertThat(MatchingRequest.fromEnvelope(envelope(payload())).failedKnockouts())
                .containsExactly("Work permit?");

        var approved = payload();
        approved.put("eligibilityApproved", true);
        assertThat(MatchingRequest.fromEnvelope(envelope(approved)).failedKnockouts())
                .isEmpty();
    }

    @Test
    void rubricHashChangesWithTheJobVersionButNotWithTheEvent() {
        var base = MatchingRequest.fromEnvelope(envelope(payload()));
        var other = new HashMap<>(envelope(payload()));
        other.put("eventId", "evt-2");
        assertThat(MatchingRequest.fromEnvelope(other).rubricHash()).isEqualTo(base.rubricHash());

        var changed = payload();
        changed.put("jobVersion", 4);
        assertThat(MatchingRequest.fromEnvelope(envelope(changed)).rubricHash()).isNotEqualTo(base.rubricHash());
    }

    @Test
    void requiresAnApplicationAndAResume() {
        var broken = envelope(Map.of("applicationId", "app-1"));
        assertThatThrownBy(() -> MatchingRequest.fromEnvelope(broken)).isInstanceOf(IllegalArgumentException.class);
    }
}
