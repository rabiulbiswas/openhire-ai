package com.openhire.applicationservice.service;

import static com.openhire.support.Records.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bson.Document;

/** Builds the {@code matching.requested.v1} command the AI workers consume from the job snapshot and the answers. */
final class MatchingCommands {
    static final String TYPE = "matching.requested.v1";

    private MatchingCommands() {}

    static int jobVersion(Document row) {
        return doc(row.get("jobSnapshot")).get("version") instanceof Number n ? n.intValue() : 1;
    }

    static Document command(String tenant, Document row) {
        var snapshot = doc(row.get("jobSnapshot"));
        var must = ((List<?>) snapshot.getOrDefault("mustHaveSkills", List.of()))
                .stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        var rubric = new ArrayList<Document>();
        double total = 0;
        for (var item : documents(snapshot.get("rubric"))) {
            var label = text(item, "label");
            if (label.isBlank()) continue;
            double weight = item.get("weight") instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : 1;
            total += weight;
            var declared = text(item, "requirement").toUpperCase(Locale.ROOT);
            var requirement = declared.equals("MUST") || declared.equals("NICE")
                    ? declared
                    : must.stream().anyMatch(skill -> mentions(label, skill)) ? "MUST" : "NICE";
            rubric.add(new Document("id", text(item, "id").isBlank() ? hash(label) : text(item, "id"))
                    .append("label", label)
                    .append("weight", weight)
                    .append("requirement", requirement));
        }
        double fallback = rubric.isEmpty() ? 1 : total / rubric.size();
        for (String skill : must)
            if (rubric.stream().noneMatch(c -> mentions(text(c, "label"), skill)))
                rubric.add(new Document("id", hash(skill))
                        .append("label", skill)
                        .append("weight", fallback)
                        .append("requirement", "MUST"));
        var knockouts = documents(snapshot.get("knockoutQuestions")).stream()
                .map(q -> new Document("id", text(q, "id"))
                        .append("question", text(q, "question"))
                        .append("requiredAnswer", text(q, "requiredAnswer")))
                .toList();
        var payload = new Document("applicationId", row.getString("_id"))
                .append("candidateId", text(row, "candidateId"))
                .append("candidateName", text(row, "name"))
                .append("resumeId", text(row, "resumeId"))
                .append("jobId", text(row, "jobId"))
                .append("jobTitle", text(row, "jobTitle"))
                .append("jobVersion", jobVersion(row))
                .append("rubric", rubric)
                .append("mustHaveSkills", must)
                .append("knockoutQuestions", knockouts)
                .append("answers", doc(row.get("answers")))
                .append("eligibilityApproved", Boolean.TRUE.equals(row.get("eligibilityApproved")));
        return com.openhire.support.Records.event(TYPE, tenant, "Application", row.getString("_id"), payload);
    }

    private static boolean mentions(String label, String skill) {
        return label.toLowerCase(Locale.ROOT).contains(skill.toLowerCase(Locale.ROOT));
    }
}
