package com.openhire.aiworkers.matching.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MatchingRequestFixtures {
    private MatchingRequestFixtures() {}

    static Map<String, Object> payload(String applicationId) {
        var payload = new HashMap<String, Object>();
        payload.put("applicationId", applicationId);
        payload.put("candidateId", "cand-" + applicationId);
        payload.put("candidateName", "Priya Das");
        payload.put("resumeId", "res-1");
        payload.put("jobId", "job-1");
        payload.put("jobTitle", "Backend Engineer");
        payload.put("jobVersion", 2);
        payload.put(
                "rubric",
                List.of(
                        Map.of("id", "r-java", "label", "Java", "weight", 2, "requirement", "MUST"),
                        Map.of("id", "r-kafka", "label", "Kafka", "weight", 2, "requirement", "MUST"),
                        Map.of("id", "r-payments", "label", "Payments domain", "weight", 1, "requirement", "NICE")));
        payload.put("mustHaveSkills", List.of("Java", "Kafka"));
        payload.put(
                "knockoutQuestions",
                List.of(Map.of("id", "permit", "question", "Work permit?", "requiredAnswer", "yes")));
        payload.put("answers", Map.of("permit", "yes"));
        payload.put("eligibilityApproved", false);
        return payload;
    }
}
