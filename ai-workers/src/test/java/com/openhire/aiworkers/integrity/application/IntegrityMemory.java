package com.openhire.aiworkers.integrity.application;

import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.ApplicationDirectory;
import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.ApplicationInfo;
import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.IdentityEntry;
import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.IdentityIndex;
import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.SignalStore;
import com.openhire.aiworkers.integrity.domain.Signal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class IntegrityMemory {
    private IntegrityMemory() {}

    static final class Index implements IdentityIndex {
        final List<IdentityEntry> entries = new ArrayList<>();

        public void register(IdentityEntry entry) {
            entries.removeIf(e -> e.applicationId().equals(entry.applicationId())
                    && e.kind().equals(entry.kind())
                    && e.valueHash().equals(entry.valueHash()));
            entries.add(entry);
        }

        public List<IdentityEntry> others(String tenantId, String kind, String valueHash, String candidateId) {
            return entries.stream()
                    .filter(e -> e.tenantId().equals(tenantId)
                            && e.kind().equals(kind)
                            && e.valueHash().equals(valueHash)
                            && !e.candidateId().equals(candidateId))
                    .toList();
        }
    }

    static final class Signals implements SignalStore {
        final Map<String, Signal> rows = new LinkedHashMap<>();
        final List<Map<String, Object>> events = new ArrayList<>();
        final List<String> dismissals = new ArrayList<>();

        public boolean insertIfAbsent(Signal signal, Map<String, Object> event) {
            if (rows.containsKey(signal.id())) return false;
            rows.put(signal.id(), signal);
            events.add(event);
            return true;
        }

        public List<Signal> byApplication(String tenantId, String applicationId) {
            return rows.values().stream()
                    .filter(s ->
                            s.tenantId().equals(tenantId) && s.applicationId().equals(applicationId))
                    .toList();
        }

        public Optional<Signal> find(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id)).filter(s -> s.tenantId().equals(tenantId));
        }

        public Optional<Signal> dismiss(
                String tenantId, String id, String actor, String reason, Map<String, Object> event) {
            var s = rows.get(id);
            if (s == null || !s.tenantId().equals(tenantId)) return Optional.empty();
            if (s.status().equals(Signal.RAISED)) {
                s = new Signal(
                        s.id(),
                        s.tenantId(),
                        s.applicationId(),
                        s.candidateId(),
                        s.resumeId(),
                        s.type(),
                        s.severity(),
                        s.confidence(),
                        s.evidence(),
                        Signal.DISMISSED,
                        s.createdAt(),
                        actor,
                        reason,
                        Instant.now());
                rows.put(id, s);
                events.add(event);
                dismissals.add(s.type() + ":" + reason);
            }
            return Optional.of(s);
        }
    }

    static final class Applications implements ApplicationDirectory {
        final Map<String, ApplicationInfo> rows = new LinkedHashMap<>();

        public ApplicationInfo application(String tenantId, String applicationId) {
            return rows.get(applicationId);
        }
    }
}
