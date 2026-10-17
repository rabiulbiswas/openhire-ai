package com.openhire.talent;

import com.openhire.platform.audit.AuditRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** An audit recorder over an in-memory outbox, so tests can read back what was recorded. */
public final class AuditProbe {
    private final List<AuditRecorder.Outbox.Pending> stored = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private final boolean failing;

    private AuditProbe(boolean failing) {
        this.failing = failing;
    }

    public static AuditProbe working() {
        return new AuditProbe(false);
    }

    /** A recorder whose store always fails, to prove auditing never breaks the business action. */
    public static AuditProbe broken() {
        return new AuditProbe(true);
    }

    public AuditRecorder recorder() {
        var outbox = new AuditRecorder.Outbox() {
            @Override
            public void add(String eventId, String tenantId, String body) {
                if (failing) throw new IllegalStateException("store down");
                stored.add(new Pending(eventId, tenantId, body));
            }

            @Override
            public List<Pending> pending(int limit) {
                return stored;
            }

            @Override
            public void remove(String eventId) {}
        };
        return new AuditRecorder(outbox, (key, body) -> {}, "test-service", true);
    }

    /** The payloads recorded so far, in order. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> payloads() {
        return stored.stream()
                .map(item ->
                        (Map<String, Object>) json.readValue(item.json(), new TypeReference<Map<String, Object>>() {})
                                .get("payload"))
                .toList();
    }

    public List<String> rawJson() {
        return stored.stream().map(AuditRecorder.Outbox.Pending::json).toList();
    }
}
