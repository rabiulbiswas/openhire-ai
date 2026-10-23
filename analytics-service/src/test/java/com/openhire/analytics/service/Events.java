package com.openhire.analytics.service;

import com.openhire.analytics.domain.Envelope;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Builds the events the real producers emit, with the same type names and payload fields. */
final class Events {
    static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    private Events() {}

    static Map<String, Object> map(Object... pairs) {
        var result = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static Envelope event(String type, String tenant, String aggregate, Instant at, Map<String, Object> payload) {
        return new Envelope(UUID.randomUUID().toString(), type, tenant, aggregate, at, payload);
    }

    static Envelope submitted(String tenant, String app, String job, String source, Instant at) {
        return event("ApplicationSubmitted", tenant, app, at, map("jobId", job, "source", source));
    }

    static Envelope screened(String tenant, String app, Instant at) {
        return event("ApplicationScreened", tenant, app, at, map("score", 70));
    }

    static Envelope stage(String tenant, String app, String from, String to, String actor, Instant at) {
        return event("ApplicationStageChanged", tenant, app, at, map("from", from, "to", to, "actorId", actor));
    }

    static Envelope override(String tenant, String app, String actor, Object reasonCode, Instant at) {
        var payload = map("actorId", actor, "score", 55);
        if (reasonCode != null) payload.put("reasonCode", reasonCode);
        return event("AssessmentOverridden", tenant, app, at, payload);
    }

    static Envelope assessment(
            String tenant, String app, String id, String status, int score, String confidence, Instant at) {
        return event(
                "assessment.completed.v1",
                tenant,
                app,
                at,
                map(
                        "applicationId",
                        app,
                        "assessmentId",
                        id,
                        "status",
                        status,
                        "score",
                        score,
                        "confidence",
                        confidence,
                        "jobId",
                        "j1",
                        "provenance",
                        map("model", "qwen2.5:3b")));
    }

    static Envelope offer(String type, String tenant, String offer, String app, String job, Instant at) {
        return event(
                type,
                tenant,
                offer,
                at,
                map("offerId", offer, "applicationId", app, "jobId", job, "jobTitle", "Engineer " + job));
    }

    static Envelope ai(
            String tenant, String task, String model, String outcome, long latency, double cost, Instant at) {
        return event(
                "ai.request.completed.v1",
                tenant,
                UUID.randomUUID().toString(),
                at,
                map(
                        "task",
                        task,
                        "model",
                        model,
                        "outcome",
                        outcome,
                        "latencyMs",
                        latency,
                        "promptTokens",
                        100,
                        "completionTokens",
                        20,
                        "costInr",
                        cost,
                        "createdAt",
                        at.toString()));
    }

    static Instant days(long days) {
        return T0.plus(Duration.ofDays(days));
    }
}
