package com.openhire.applicationservice.service;

import static com.openhire.support.Records.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;

/**
 * Decides how an {@code assessment.completed.v1} result folds into an application. The rules, in one place:
 * COMPLETE replaces, PARTIAL never replaces a COMPLETE, results for an older job version or another resume are
 * ignored, human overrides and integrity flags are kept, and the stage is never part of the result.
 */
public final class AiAssessments {
    private AiAssessments() {}

    /** The fields to set on the application, or empty when the event must be ignored. Never contains the stage. */
    public static Optional<Map<String, Object>> merge(Document row, Document payload) {
        var status = text(payload, "status");
        if (!List.of("COMPLETE", "PARTIAL").contains(status) || !row.containsKey("assessment")) return Optional.empty();
        int version = payload.get("jobVersion") instanceof Number n ? n.intValue() : 1;
        if (version < MatchingCommands.jobVersion(row)) return Optional.empty();
        var resumeId = text(payload, "resumeId");
        if (!resumeId.isBlank() && !text(row, "resumeId").isBlank() && !resumeId.equals(text(row, "resumeId")))
            return Optional.empty();
        var existing = doc(row.get("assessment"));
        var existingStatus = text(existing, "status").isBlank() ? "PARTIAL" : text(existing, "status");
        if (existingStatus.equals("COMPLETE")) {
            if (status.equals("PARTIAL")) return Optional.empty();
            if (existing.get("jobVersion") instanceof Number n && n.intValue() > version) return Optional.empty();
            var created = text(doc(payload.get("provenance")), "createdAt");
            if (created.compareTo(text(doc(existing.get("provenance")), "createdAt")) < 0) return Optional.empty();
        }
        var provenance = doc(payload.get("provenance"));
        var assessment = new Document("applicationId", row.getString("_id"))
                .append("assessmentId", text(payload, "assessmentId"))
                .append("status", status)
                .append(
                        "label",
                        status.equals("COMPLETE") && !text(payload, "label").isBlank()
                                ? text(payload, "label")
                                : SubmissionService.PARTIAL_LABEL)
                .append("score", payload.get("score"))
                .append("confidence", text(payload, "confidence"))
                .append(
                        "modelVersion",
                        text(provenance, "promptKey").isBlank()
                                ? text(provenance, "model")
                                : text(provenance, "model") + " / " + text(provenance, "promptKey") + "@"
                                        + text(provenance, "promptVersion"))
                .append("rubricVersion", text(payload, "rubricVersion"))
                .append("jobVersion", version)
                .append("provenance", provenance)
                .append("criteria", criteria(payload.get("criteria")))
                .append("strengths", payload.getOrDefault("strengths", List.of()))
                .append("gapsToProbe", payload.getOrDefault("gapsToProbe", List.of()))
                .append("hardGaps", payload.getOrDefault("hardGaps", List.of()))
                .append("resumeExcerpt", text(existing, "resumeExcerpt"));
        var flags = documents(row.get("integrityFlags"));
        assessment.append("integrityFlags", flags);
        if (!flags.isEmpty()) assessment.append("integrityFlag", new Document("reason", text(flags.get(0), "reason")));
        var overridden = existing.get("overridden");
        if (overridden != null) assessment.append("overridden", overridden);
        var sets = new LinkedHashMap<String, Object>();
        sets.put("assessment", assessment);
        sets.put("score", overridden != null ? doc(overridden).get("newScore") : payload.get("score"));
        sets.put("updatedAt", now());
        return Optional.of(sets);
    }

    private static List<Document> criteria(Object value) {
        var result = new ArrayList<Document>();
        for (var item : documents(value))
            result.add(new Document("id", text(item, "id"))
                    .append("label", text(item, "label"))
                    .append("requirement", text(item, "requirement"))
                    .append("weight", item.get("weight"))
                    .append("score", item.get("score"))
                    .append(
                            "evidence",
                            item.get("evidence") instanceof List<?> quotes
                                    ? quotes.stream().map(Object::toString).toList()
                                    : List.of())
                    .append("grounded", Boolean.TRUE.equals(item.get("grounded"))));
        return result;
    }

    /** The staff view of an application's assessment: stored fields plus decision data and current integrity flags. */
    public static Document view(Document row) {
        var assessment = new Document(doc(row.get("assessment")));
        assessment.put("decidedBy", text(row, "decidedBy"));
        if (java.util.Set.of("SHORTLISTED", "REJECTED").contains(text(row, "stage")))
            assessment.put("decidedAction", text(row, "stage"));
        if (text(assessment, "status").isBlank()) assessment.put("status", "PARTIAL");
        if (text(assessment, "label").isBlank()) assessment.put("label", SubmissionService.PARTIAL_LABEL);
        var flags = documents(row.get("integrityFlags"));
        assessment.put("integrityFlags", flags);
        if (flags.isEmpty()) assessment.remove("integrityFlag");
        else assessment.put("integrityFlag", new Document("reason", text(flags.get(0), "reason")));
        return assessment;
    }
}
