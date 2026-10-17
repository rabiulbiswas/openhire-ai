package com.openhire.compliance.application;

import com.openhire.compliance.application.port.CompliancePorts.AuditFilter;
import com.openhire.compliance.application.port.CompliancePorts.AuditSink;
import com.openhire.compliance.application.port.CompliancePorts.AuditStore;
import com.openhire.compliance.domain.AuditChain;
import com.openhire.compliance.domain.AuditEntry;
import com.openhire.compliance.domain.Times;
import com.openhire.compliance.domain.Ulid;
import com.openhire.platform.audit.AuditRecord;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The append-only, hash-chained audit ledger. Consumes audit events, serves queries, verification and export. */
public class AuditLedger {
    public static final String EVENT_TYPE = "audit.event.recorded.v1";
    private static final int MAX_ATTEMPTS = 200;
    private static final int BATCH = 500;

    public record Page(List<Map<String, Object>> data, String nextCursor, boolean hasMore) {}

    private final AuditStore store;
    private final AuditSink sink;
    private final Clock clock;

    public AuditLedger(AuditStore store, AuditSink sink, Clock clock) {
        this.store = store;
        this.sink = sink;
        this.clock = clock;
    }

    /** Appends the event to its tenant's chain; true when stored, false when ignored (other type or duplicate). */
    public boolean ingest(Map<String, Object> envelope) {
        if (!EVENT_TYPE.equals(Maps.text(envelope, "eventType"))) return false;
        var tenant = required(envelope, "tenantId");
        var eventId = required(envelope, "eventId");
        var payload = Maps.map(envelope.get("payload"));
        var actor = Maps.map(payload.get("actor"));
        var resource = Maps.map(payload.get("resource"));
        var action = required(payload, "action");
        var actorView = Maps.of(
                "type", Maps.text(actor, "type", "SYSTEM"),
                "id", Maps.text(actor, "id", "system"),
                "name", Maps.text(actor, "name"));
        var resourceView = Maps.of("type", required(resource, "type"), "id", required(resource, "id"));
        var at = Times.normalise(Maps.text(payload, "at", Maps.text(envelope, "occurredAt")));
        if (at == null) at = Times.format(clock.instant());
        var details = Maps.copy(Maps.map(payload.get("details")));
        var source = Maps.text(envelope, "producer", "unknown");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            var last = store.last(tenant);
            long seq = last.map(AuditEntry::seq).orElse(0L) + 1;
            var prev = last.map(AuditEntry::hash).orElse(AuditChain.GENESIS);
            var entry = AuditChain.seal(new AuditEntry(
                    Ulid.next("aud"),
                    tenant,
                    seq,
                    eventId,
                    action,
                    actorView,
                    resourceView,
                    Maps.text(payload, "candidateId"),
                    Maps.text(payload, "applicationId"),
                    details,
                    source,
                    at,
                    prev,
                    null));
            switch (store.insert(entry)) {
                case INSERTED -> {
                    return true;
                }
                case DUPLICATE_EVENT -> {
                    return false;
                }
                case SEQ_CONFLICT -> Thread.onSpinWait();
            }
        }
        throw new IllegalStateException("Audit chain for " + tenant + " is too contended; the event will be retried");
    }

    public Page list(String tenantId, AuditFilter filter, String cursor, int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        Long before = cursor == null || cursor.isBlank() ? null : Long.parseLong(cursor);
        var rows = store.page(tenantId, filter, before, size + 1);
        boolean more = rows.size() > size;
        var shown = more ? rows.subList(0, size) : rows;
        return new Page(
                shown.stream().map(AuditEntry::view).toList(),
                more ? String.valueOf(shown.getLast().seq()) : null,
                more);
    }

    public AuditChain.Result verify(String tenantId) {
        var verifier = new AuditChain.Verifier();
        while (true) {
            var batch = store.after(tenantId, verifier.lastSeq(), null, null, BATCH);
            if (batch.isEmpty()) return verifier.result();
            if (!verifier.feed(batch)) return verifier.result();
            if (batch.size() < BATCH) return verifier.result();
        }
    }

    /** Streams the records oldest first. The export itself is audited before the first record leaves. */
    public void export(String tenantId, Actor actor, String from, String to, Consumer<AuditEntry> out) {
        var fromAt = Times.normalise(from);
        var toAt = Times.normalise(to);
        sink.record(AuditRecord.of(tenantId, "DATA_EXPORT")
                .user(actor.id(), actor.name())
                .resource("AuditLedger", tenantId)
                .detail("scope", "audit-events")
                .detail("from", fromAt)
                .detail("to", toAt)
                .build());
        long seq = 0;
        while (true) {
            var batch = store.after(tenantId, seq, fromAt, toAt, BATCH);
            batch.forEach(out);
            if (batch.size() < BATCH) return;
            seq = batch.getLast().seq();
        }
    }

    private static String required(Map<String, Object> map, String key) {
        var value = Maps.text(map, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Audit event is missing " + key);
        return value;
    }
}
