package com.openhire.aiworkers.matching.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The {@code matching.requested.v1} command. {@link #fromEnvelope} and {@link #toEnvelope} are inverses. */
public record MatchingRequest(
        String eventId,
        String tenantId,
        String applicationId,
        String candidateId,
        String candidateName,
        String resumeId,
        String jobId,
        String jobTitle,
        int jobVersion,
        List<Criterion> rubric,
        List<String> mustHaveSkills,
        List<Knockout> knockouts,
        Map<String, String> answers,
        boolean eligibilityApproved) {

    public record Criterion(String id, String label, String requirement, double weight, String description) {
        public boolean must() {
            return "MUST".equals(requirement);
        }
    }

    public record Knockout(String id, String question, String requiredAnswer) {}

    /** Identifies the work: a redelivered or re-requested command for the same inputs has the same hash. */
    public String rubricHash() {
        var text = new StringBuilder();
        rubric.stream().sorted(java.util.Comparator.comparing(Criterion::id)).forEach(c -> text.append(c.id())
                .append('|')
                .append(c.label())
                .append('|')
                .append(c.requirement())
                .append('|')
                .append(c.weight())
                .append(';'));
        mustHaveSkills.stream().sorted().forEach(skill -> text.append(skill).append(','));
        return Hashes.sha256(text.toString(), String.valueOf(jobVersion));
    }

    @SuppressWarnings("unchecked")
    public static MatchingRequest fromEnvelope(Map<String, Object> envelope) {
        var payload = map(envelope.get("payload"));
        var rubric = new ArrayList<Criterion>();
        for (var item : list(payload.get("rubric"))) {
            var row = map(item);
            var label = text(row, "label");
            if (label.isBlank()) continue;
            var weight = row.get("weight") instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : 1.0;
            var requirement = "MUST".equals(text(row, "requirement").toUpperCase(Locale.ROOT)) ? "MUST" : "NICE";
            var id = text(row, "id").isBlank() ? Hashes.sha256(label).substring(0, 12) : text(row, "id");
            rubric.add(new Criterion(id, label, requirement, weight, text(row, "description")));
        }
        var knockouts = new ArrayList<Knockout>();
        for (var item : list(payload.get("knockoutQuestions"))) {
            var row = map(item);
            knockouts.add(new Knockout(text(row, "id"), text(row, "question"), text(row, "requiredAnswer")));
        }
        var answers = new TreeMap<String, String>();
        map(payload.get("answers")).forEach((k, v) -> answers.put(k, v == null ? "" : v.toString()));
        var applicationId = required(payload, "applicationId");
        return new MatchingRequest(
                required(envelope, "eventId"),
                required(envelope, "tenantId"),
                applicationId,
                text(payload, "candidateId"),
                text(payload, "candidateName"),
                required(payload, "resumeId"),
                text(payload, "jobId"),
                text(payload, "jobTitle"),
                payload.get("jobVersion") instanceof Number n ? n.intValue() : 1,
                List.copyOf(rubric),
                list(payload.get("mustHaveSkills")).stream()
                        .map(Object::toString)
                        .filter(s -> !s.isBlank())
                        .toList(),
                List.copyOf(knockouts),
                Map.copyOf(answers),
                Boolean.TRUE.equals(payload.get("eligibilityApproved")));
    }

    public Map<String, Object> toEnvelope() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("applicationId", applicationId);
        payload.put("candidateId", candidateId);
        payload.put("candidateName", candidateName);
        payload.put("resumeId", resumeId);
        payload.put("jobId", jobId);
        payload.put("jobTitle", jobTitle);
        payload.put("jobVersion", jobVersion);
        payload.put(
                "rubric",
                rubric.stream()
                        .map(c -> {
                            var row = new LinkedHashMap<String, Object>();
                            row.put("id", c.id());
                            row.put("label", c.label());
                            row.put("weight", c.weight());
                            row.put("requirement", c.requirement());
                            if (!c.description().isBlank()) row.put("description", c.description());
                            return row;
                        })
                        .toList());
        payload.put("mustHaveSkills", mustHaveSkills);
        payload.put(
                "knockoutQuestions",
                knockouts.stream()
                        .map(k -> Map.of("id", k.id(), "question", k.question(), "requiredAnswer", k.requiredAnswer()))
                        .toList());
        payload.put("answers", answers);
        payload.put("eligibilityApproved", eligibilityApproved);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "matching.requested.v1");
        envelope.put("tenantId", tenantId);
        envelope.put("aggregateType", "Application");
        envelope.put("aggregateId", applicationId);
        envelope.put("payload", payload);
        return envelope;
    }

    /** Stage 1: the knockout questions whose answer differs from the required one (unless a human approved). */
    public List<String> failedKnockouts() {
        if (eligibilityApproved) return List.of();
        var failed = new ArrayList<String>();
        for (var knockout : knockouts)
            if (!knockout.requiredAnswer().equalsIgnoreCase(answers.getOrDefault(knockout.id(), "")))
                failed.add(knockout.question().isBlank() ? knockout.id() : knockout.question());
        return failed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static String text(Map<String, Object> source, String key) {
        var value = source.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String required(Map<String, Object> source, String key) {
        var value = text(source, key);
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
