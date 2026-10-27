package com.openhire.jobservice.service;

import com.openhire.jobservice.domain.Job;
import com.openhire.jobservice.service.port.ApprovalClient.FieldChange;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Compare persisted job snapshots only; no model inference or synthetic impact counts. */
final class JobApprovalChanges {
    private JobApprovalChanges() {}

    static List<FieldChange> between(Job.JobVersion before, Job.JobVersion after) {
        var changes = new ArrayList<FieldChange>();
        add(changes, "Job description", before.description(), after.description());
        add(changes, "Must-have skills", before.mustHaveSkills(), after.mustHaveSkills());
        add(changes, "Qualification", before.qualification(), after.qualification());
        add(changes, "Good-to-have skills", before.goodToHaveSkills(), after.goodToHaveSkills());
        var ids = new LinkedHashSet<String>();
        before.rubric().forEach(r -> ids.add(r.id()));
        after.rubric().forEach(r -> ids.add(r.id()));
        for (var id : ids) {
            var old = before.rubric().stream()
                    .filter(r -> r.id().equals(id))
                    .findFirst()
                    .orElse(null);
            var next = after.rubric().stream()
                    .filter(r -> r.id().equals(id))
                    .findFirst()
                    .orElse(null);
            add(
                    changes,
                    "Rubric: " + (next == null ? old.label() : next.label()),
                    old == null ? null : old.label() + " " + old.weight() + "%",
                    next == null ? null : next.label() + " " + next.weight() + "%");
        }
        add(changes, "Screening questions", before.knockoutQuestions(), after.knockoutQuestions());
        add(changes, "Interview plan", before.interviewPlan(), after.interviewPlan());
        add(changes, "Headcount", before.headcount(), after.headcount());
        add(changes, "Position type", before.positionType(), after.positionType());
        add(changes, "Hiring reason", before.hiringReason(), after.hiringReason());
        add(
                changes,
                "Job category",
                before.classification() == null ? null : before.classification().categoryName(),
                after.classification() == null ? null : after.classification().categoryName());
        add(
                changes,
                "Work mode",
                before.classification() == null ? null : before.classification().workMode(),
                after.classification() == null ? null : after.classification().workMode());
        return List.copyOf(changes);
    }

    private static void add(List<FieldChange> changes, String label, Object before, Object after) {
        if (!Objects.equals(before, after)) changes.add(new FieldChange(label, text(before), text(after)));
    }

    private static String text(Object value) {
        if (value == null || value.toString().isBlank()) return "Not provided";
        if (value instanceof List<?> list)
            return list.isEmpty()
                    ? "None"
                    : String.join(
                            ", ", list.stream().map(JobApprovalChanges::text).toList());
        if (value instanceof Job.KnockoutQuestion q) return q.question() + " (required: " + q.requiredAnswer() + ")";
        if (value instanceof Job.InterviewRound r)
            return r.name() + " — " + r.durationMin() + " min; " + String.join(", ", r.interviewerNames());
        if ("NEW_POSITION".equals(value)) return "New position";
        if ("REPLACEMENT".equals(value)) return "Replacement";
        return value.toString();
    }
}
