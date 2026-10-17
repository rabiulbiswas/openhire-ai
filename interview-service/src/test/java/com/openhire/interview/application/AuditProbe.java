package com.openhire.interview.application;

import com.openhire.platform.audit.AuditRecorder;
import java.util.ArrayList;
import java.util.List;

/** An in-memory audit outbox so tests can read back exactly what a use case recorded. */
final class AuditProbe {
    private final List<String> records = new ArrayList<>();
    private boolean failing;

    final AuditRecorder recorder = new AuditRecorder(
            new AuditRecorder.Outbox() {
                public void add(String eventId, String tenantId, String json) {
                    if (failing) throw new IllegalStateException("outbox down");
                    records.add(json);
                }

                public List<Pending> pending(int limit) {
                    return List.of();
                }

                public void remove(String eventId) {}
            },
            (key, json) -> {},
            "interview-service",
            true);

    AuditProbe failing() {
        failing = true;
        return this;
    }

    List<String> records() {
        return records;
    }

    /** The one record with this action; fails if there is not exactly one. */
    String only(String action) {
        var matching = records.stream()
                .filter(json -> json.contains("\"action\":\"" + action + "\""))
                .toList();
        if (matching.size() != 1) throw new AssertionError(action + " recorded " + matching.size() + " times");
        return matching.getFirst();
    }
}
