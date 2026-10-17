package com.openhire.applicationservice.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Persisted as one Mongo document including its pending events (atomic single-document outbox). */
public record ApplicationRecord(
        String id,
        String tenantId,
        String submittedBy,
        String requestFingerprint,
        IntakeCommand intake,
        String jobTitle,
        int jobVersion,
        ApplicationStage stage,
        Instant submittedAt,
        Instant updatedAt,
        Long revision,
        List<Decision> decisions,
        List<ApplicationEvent> pendingEvents) {

    public ApplicationRecord {
        decisions = List.copyOf(decisions);
        pendingEvents = List.copyOf(pendingEvents);
    }

    public static ApplicationRecord submit(
            String id, String tenant, String actor, IntakeCommand input, String jobTitle, int jobVersion, Instant now) {
        var event = ApplicationEvent.create(
                "ApplicationSubmitted",
                tenant,
                id,
                now,
                Map.of(
                        "jobId",
                        input.jobId(),
                        "jobVersion",
                        jobVersion,
                        "source",
                        "RECRUITER",
                        "processingConsent",
                        true,
                        "consentVersion",
                        input.consentVersion()));
        return new ApplicationRecord(
                id,
                tenant,
                actor,
                input.fingerprint(),
                input,
                jobTitle,
                jobVersion,
                ApplicationStage.APPLIED,
                now,
                now,
                null,
                List.of(),
                List.of(event));
    }

    public ApplicationRecord transition(ApplicationStage next, String actor, String reason, Instant now) {
        stage.requireTransitionTo(next);
        reason = IntakeCommand.required(reason, "reason", 1000);
        var history = new ArrayList<>(decisions);
        history.add(new Decision(stage, next, actor, reason, now));
        var events = new ArrayList<>(pendingEvents);
        events.add(ApplicationEvent.create(
                "ApplicationStageChanged",
                tenantId,
                id,
                now,
                Map.of("jobId", intake.jobId(), "from", stage.name(), "to", next.name(), "actorId", actor)));
        return new ApplicationRecord(
                id,
                tenantId,
                submittedBy,
                requestFingerprint,
                intake,
                jobTitle,
                jobVersion,
                next,
                submittedAt,
                now,
                revision,
                history,
                events);
    }

    public record Decision(ApplicationStage from, ApplicationStage to, String actorId, String reason, Instant at) {}
}
