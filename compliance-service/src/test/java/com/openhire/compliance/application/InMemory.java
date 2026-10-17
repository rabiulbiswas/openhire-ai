package com.openhire.compliance.application;

import com.openhire.compliance.application.port.CompliancePorts.*;
import com.openhire.compliance.domain.AuditEntry;
import com.openhire.platform.audit.AuditRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory ports with the same semantics as the Mongo adapters. */
public final class InMemory {
    private InMemory() {}

    @SuppressWarnings("unchecked")
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, copy(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>();
            list.forEach(v -> out.add(copy(v)));
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copyMap(Map<String, Object> map) {
        return (Map<String, Object>) copy(map);
    }

    public static final class MutableClock extends java.time.Clock {
        Instant now = Instant.parse("2026-10-15T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }
    }

    public static final class Audit implements AuditStore {
        final List<AuditEntry> rows = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<AuditEntry> last(String tenantId) {
            synchronized (rows) {
                return rows.stream()
                        .filter(e -> e.tenantId().equals(tenantId))
                        .max(Comparator.comparingLong(AuditEntry::seq));
            }
        }

        @Override
        public InsertResult insert(AuditEntry entry) {
            synchronized (rows) {
                if (rows.stream().anyMatch(e -> e.eventId().equals(entry.eventId())))
                    return InsertResult.DUPLICATE_EVENT;
                if (rows.stream().anyMatch(e -> e.tenantId().equals(entry.tenantId()) && e.seq() == entry.seq()))
                    return InsertResult.SEQ_CONFLICT;
                rows.add(entry);
                return InsertResult.INSERTED;
            }
        }

        @Override
        public List<AuditEntry> page(String tenantId, AuditFilter f, Long beforeSeq, int limit) {
            synchronized (rows) {
                return rows.stream()
                        .filter(e -> e.tenantId().equals(tenantId))
                        .filter(e ->
                                f.actor() == null || f.actor().equals(e.actor().get("id")))
                        .filter(e -> f.action() == null || f.action().equals(e.action()))
                        .filter(e -> f.resourceType() == null
                                || f.resourceType().equals(e.resource().get("type")))
                        .filter(e -> f.resourceId() == null
                                || f.resourceId().equals(e.resource().get("id")))
                        .filter(e -> f.candidateId() == null || f.candidateId().equals(e.candidateId()))
                        .filter(e ->
                                f.applicationId() == null || f.applicationId().equals(e.applicationId()))
                        .filter(e -> f.from() == null || e.at().compareTo(f.from()) >= 0)
                        .filter(e -> f.to() == null || e.at().compareTo(f.to()) <= 0)
                        .filter(e -> beforeSeq == null || e.seq() < beforeSeq)
                        .sorted(Comparator.comparingLong(AuditEntry::seq).reversed())
                        .limit(limit)
                        .toList();
            }
        }

        @Override
        public List<AuditEntry> after(String tenantId, long afterSeq, String from, String to, int limit) {
            synchronized (rows) {
                return rows.stream()
                        .filter(e -> e.tenantId().equals(tenantId) && e.seq() > afterSeq)
                        .filter(e -> from == null || e.at().compareTo(from) >= 0)
                        .filter(e -> to == null || e.at().compareTo(to) <= 0)
                        .sorted(Comparator.comparingLong(AuditEntry::seq))
                        .limit(limit)
                        .toList();
            }
        }

        @Override
        public long countSince(String tenantId, String from) {
            return rows.stream()
                    .filter(e -> e.tenantId().equals(tenantId) && e.at().compareTo(from) >= 0)
                    .count();
        }

        void replace(int index, AuditEntry entry) {
            rows.set(index, entry);
        }
    }

    public static final class Activity implements ActivityStore {
        final Map<String, Map<String, Object>> docs = new ConcurrentHashMap<>();

        @Override
        public Optional<Map<String, Object>> find(String tenantId, String applicationId) {
            return Optional.ofNullable(docs.get(tenantId + "|" + applicationId)).map(InMemory::copyMap);
        }

        @Override
        public synchronized boolean save(Map<String, Object> doc) {
            long version = ((Number) doc.get("version")).longValue();
            var stored = docs.get((String) doc.get("_id"));
            long current = stored == null ? 0 : ((Number) stored.get("version")).longValue();
            if (current != version) return false;
            var next = copyMap(doc);
            next.put("version", version + 1);
            docs.put((String) doc.get("_id"), next);
            doc.put("version", version + 1);
            return true;
        }

        private List<Map<String, Object>> where(java.util.function.Predicate<Map<String, Object>> p) {
            return docs.values().stream().filter(p).map(InMemory::copyMap).toList();
        }

        @Override
        public List<Map<String, Object>> byCandidate(String tenantId, String candidateId) {
            return where(d -> tenantId.equals(d.get("tenantId")) && candidateId.equals(d.get("candidateId")));
        }

        @Override
        public List<Map<String, Object>> byJob(String tenantId, String jobId) {
            return where(d -> tenantId.equals(d.get("tenantId")) && jobId.equals(d.get("jobId")));
        }

        @Override
        public List<Map<String, Object>> all(String tenantId) {
            return where(d -> tenantId.equals(d.get("tenantId")));
        }

        @Override
        public List<Map<String, Object>> idleSince(String tenantId, String cutoff, int limit) {
            return where(d -> tenantId.equals(d.get("tenantId"))
                            && d.get("lastActivityAt") != null
                            && ((String) d.get("lastActivityAt")).compareTo(cutoff) < 0)
                    .stream()
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<String> tenants() {
            return docs.values().stream()
                    .map(d -> (String) d.get("tenantId"))
                    .distinct()
                    .toList();
        }

        @Override
        public void deleteByCandidate(String tenantId, String candidateId) {
            docs.values().removeIf(d -> tenantId.equals(d.get("tenantId")) && candidateId.equals(d.get("candidateId")));
        }
    }

    public static final class Decisions implements DecisionStore {
        final Map<String, Map<String, Object>> rows = new ConcurrentHashMap<>();

        @Override
        public void save(Map<String, Object> decision) {
            rows.put((String) decision.get("_id"), copyMap(decision));
        }

        @Override
        public Optional<Map<String, Object>> find(String tenantId, String id) {
            return Optional.ofNullable(rows.get(tenantId + "|" + id)).map(InMemory::copyMap);
        }

        @Override
        public List<Map<String, Object>> list(
                String tenantId, String candidateId, String applicationId, String task, String from, int limit) {
            return rows.values().stream()
                    .filter(d -> tenantId.equals(d.get("tenantId")))
                    .filter(d -> candidateId == null || candidateId.equals(d.get("candidateId")))
                    .filter(d -> applicationId == null || applicationId.equals(d.get("applicationId")))
                    .filter(d -> task == null || task.equals(d.get("task")))
                    .filter(d -> from == null || ((String) d.get("at")).compareTo(from) >= 0)
                    .sorted(Comparator.comparing((Map<String, Object> d) -> (String) d.get("at"))
                            .reversed())
                    .limit(limit)
                    .map(InMemory::copyMap)
                    .toList();
        }

        @Override
        public void deleteByCandidate(String tenantId, String candidateId) {
            rows.values().removeIf(d -> tenantId.equals(d.get("tenantId")) && candidateId.equals(d.get("candidateId")));
        }
    }

    public static final class Consent implements ConsentStore {
        final List<Map<String, Object>> rows = new ArrayList<>();

        @Override
        public List<Map<String, Object>> texts(String tenantId) {
            return rows.stream()
                    .filter(t -> tenantId.equals(t.get("tenantId")))
                    .map(InMemory::copyMap)
                    .toList();
        }

        @Override
        public Optional<Map<String, Object>> active(String tenantId, String purpose) {
            return rows.stream()
                    .filter(t -> tenantId.equals(t.get("tenantId"))
                            && purpose.equals(t.get("purpose"))
                            && "ACTIVE".equals(t.get("status")))
                    .findFirst()
                    .map(InMemory::copyMap);
        }

        @Override
        public void publish(Map<String, Object> text) {
            rows.stream()
                    .filter(t -> t.get("tenantId").equals(text.get("tenantId"))
                            && t.get("purpose").equals(text.get("purpose")))
                    .forEach(t -> t.put("status", "RETIRED"));
            rows.add(copyMap(text));
        }
    }

    public static final class Privacy implements PrivacyStore {
        final Map<String, Map<String, Object>> requests = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> sections = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> exports = new ConcurrentHashMap<>();

        @Override
        public synchronized void insert(Map<String, Object> request) {
            requests.put((String) request.get("_id"), copyMap(request));
        }

        @Override
        public synchronized Optional<Map<String, Object>> find(String tenantId, String id) {
            return Optional.ofNullable(requests.get(id))
                    .filter(r -> tenantId.equals(r.get("tenantId")))
                    .map(InMemory::copyMap);
        }

        @Override
        public synchronized List<Map<String, Object>> list(
                String tenantId, String status, String candidateId, int limit) {
            return requests.values().stream()
                    .filter(r -> tenantId.equals(r.get("tenantId")))
                    .filter(r -> status == null || status.equals(r.get("status")))
                    .filter(r -> candidateId == null || candidateId.equals(r.get("candidateId")))
                    .sorted(Comparator.comparing((Map<String, Object> r) -> (String) r.get("createdAt"))
                            .reversed())
                    .limit(limit)
                    .map(InMemory::copyMap)
                    .toList();
        }

        @Override
        public synchronized List<Map<String, Object>> withStatus(String status, int limit) {
            return requests.values().stream()
                    .filter(r -> status.equals(r.get("status")))
                    .limit(limit)
                    .map(InMemory::copyMap)
                    .toList();
        }

        @Override
        public synchronized boolean replace(Map<String, Object> request, List<String> from) {
            var current = requests.get(request.get("_id"));
            if (current == null || !from.contains(current.get("status"))) return false;
            requests.put((String) request.get("_id"), copyMap(request));
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Optional<Map<String, Object>> recordAck(
                String tenantId, String id, String service, Map<String, Object> entry, Map<String, Object> section) {
            var current = requests.get(id);
            if (current == null
                    || !tenantId.equals(current.get("tenantId"))
                    || !"IN_PROGRESS".equals(current.get("status"))) return Optional.empty();
            var participants = (Map<String, Object>) current.get("participants");
            var participant = (Map<String, Object>) participants.get(service);
            if (participant == null || !"PENDING".equals(participant.get("status"))) return Optional.empty();
            participants.put(service, copyMap(entry));
            if (section != null)
                ((Map<String, Object>) sections.computeIfAbsent(id, k -> new LinkedHashMap<>()))
                        .put(service, copy(section));
            return Optional.of(copyMap(current));
        }

        @Override
        public synchronized void markStuck(String tenantId, String id) {
            var current = requests.get(id);
            if (current != null && "IN_PROGRESS".equals(current.get("status"))) current.put("stuck", true);
        }

        @Override
        public synchronized void anonymise(String tenantId, String candidateId, String candidateIdHash) {
            requests.values().stream()
                    .filter(r -> candidateId.equals(r.get("candidateId")))
                    .forEach(r -> {
                        r.remove("candidateId");
                        r.put("candidateIdHash", candidateIdHash);
                    });
            requests.values().stream()
                    .filter(r -> candidateId.equals(r.get("requestedBy")))
                    .forEach(r -> r.put("requestedBy", candidateIdHash));
            exports.values().removeIf(e -> candidateId.equals(e.get("candidateId")));
        }

        @Override
        public void saveExport(String tenantId, String requestId, Map<String, Object> export, String purgeAt) {
            exports.put(requestId, copyMap(export));
        }

        @Override
        public Optional<Map<String, Object>> export(String tenantId, String requestId) {
            if (exports.containsKey(requestId)) return Optional.of(copyMap(exports.get(requestId)));
            return Optional.ofNullable(sections.get(requestId)).map(s -> Map.of("sections", copy(s)));
        }

        @Override
        public synchronized long countOpen(String tenantId) {
            return requests.values().stream()
                    .filter(r -> tenantId.equals(r.get("tenantId"))
                            && List.of("REQUESTED", "BLOCKED_BY_LEGAL_HOLD", "IN_PROGRESS")
                                    .contains(r.get("status")))
                    .count();
        }

        @Override
        public synchronized long countStuck(String tenantId) {
            return requests.values().stream()
                    .filter(r -> tenantId.equals(r.get("tenantId")) && Boolean.TRUE.equals(r.get("stuck")))
                    .count();
        }
    }

    public static final class Holds implements HoldStore {
        final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        @Override
        public void insert(Map<String, Object> hold) {
            rows.put((String) hold.get("_id"), copyMap(hold));
        }

        @Override
        public Optional<Map<String, Object>> find(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id))
                    .filter(h -> tenantId.equals(h.get("tenantId")))
                    .map(InMemory::copyMap);
        }

        @Override
        public List<Map<String, Object>> list(String tenantId, String status) {
            return rows.values().stream()
                    .filter(h ->
                            tenantId.equals(h.get("tenantId")) && (status == null || status.equals(h.get("status"))))
                    .map(InMemory::copyMap)
                    .toList();
        }

        @Override
        public void replace(Map<String, Object> hold) {
            rows.put((String) hold.get("_id"), copyMap(hold));
        }

        @Override
        public List<Map<String, Object>> active(String tenantId) {
            return list(tenantId, "ACTIVE");
        }
    }

    public static final class Policies implements PolicyStore {
        final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        @Override
        public List<Map<String, Object>> overrides(String tenantId) {
            return rows.values().stream()
                    .filter(p -> tenantId.equals(p.get("tenantId")))
                    .map(InMemory::copyMap)
                    .toList();
        }

        @Override
        public void save(Map<String, Object> override) {
            rows.put((String) override.get("_id"), copyMap(override));
        }
    }

    public static final class SelfIds implements SelfIdentificationStore {
        final Map<String, Map<String, String>> rows = new ConcurrentHashMap<>();

        @Override
        public void save(String tenantId, String applicationId, String candidateId, String ciphertext, String at) {
            rows.put(
                    tenantId + "|" + applicationId,
                    Map.of(
                            "candidateId",
                            candidateId,
                            "ciphertext",
                            ciphertext,
                            "applicationId",
                            applicationId,
                            "tenantId",
                            tenantId));
        }

        @Override
        public Optional<String> ciphertext(String tenantId, String applicationId) {
            return Optional.ofNullable(rows.get(tenantId + "|" + applicationId)).map(r -> r.get("ciphertext"));
        }

        @Override
        public Map<String, String> ciphertexts(String tenantId, List<String> applicationIds) {
            var out = new LinkedHashMap<String, String>();
            applicationIds.forEach(id -> ciphertext(tenantId, id).ifPresent(c -> out.put(id, c)));
            return out;
        }

        @Override
        public void deleteByCandidate(String tenantId, String candidateId, List<String> applicationIds) {
            rows.values().removeIf(r -> tenantId.equals(r.get("tenantId")) && candidateId.equals(r.get("candidateId")));
        }
    }

    public static final class Snapshots implements FairnessStore {
        final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        @Override
        public Optional<Map<String, Object>> latest(String tenantId, String jobId, String dimension) {
            return Optional.ofNullable(rows.get(tenantId + "|" + jobId + "|" + dimension));
        }

        @Override
        public void save(Map<String, Object> snapshot) {
            rows.put((String) snapshot.get("_id"), snapshot);
        }
    }

    public static final class XorCipher implements Cipher {
        @Override
        public String encrypt(String plaintext, String context) {
            return "enc:" + new StringBuilder(plaintext).reverse();
        }

        @Override
        public String decrypt(String ciphertext, String context) {
            return new StringBuilder(ciphertext.substring(4)).reverse().toString();
        }
    }

    record Published(String tenantId, String type, Map<String, Object> payload) {}

    public static final class EventLog implements Events {
        final List<Published> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(
                String tenantId,
                String aggregateType,
                String aggregateId,
                String eventType,
                Map<String, Object> payload) {
            events.add(new Published(tenantId, eventType, payload));
        }

        List<Published> of(String type) {
            return events.stream().filter(e -> e.type().equals(type)).toList();
        }
    }

    public static final class Sink implements AuditSink {
        final List<AuditRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }

        public List<AuditRecord> of(String action) {
            return records.stream().filter(r -> r.action().equals(action)).toList();
        }
    }

    public static final class ErasedSet implements Erased {
        final java.util.Set<String> ids = ConcurrentHashMap.newKeySet();

        @Override
        public boolean isErased(String tenantId, String id) {
            return ids.contains(tenantId + "|" + id);
        }

        @Override
        public void mark(String tenantId, String id, String requestId) {
            ids.add(tenantId + "|" + id);
        }
    }

    public static final class Directory implements ApplicationDirectory {
        final Map<String, AppInfo> apps = new ConcurrentHashMap<>();
        final Map<String, String> receipts = new ConcurrentHashMap<>();
        public boolean down;

        public void add(String applicationId, String candidateId, String name, String jobId, String stage) {
            apps.put(applicationId, new AppInfo(applicationId, candidateId, name, jobId, "Engineer", stage, "CAREER"));
            receipts.put(applicationId, "receipt-" + applicationId);
        }

        @Override
        public Optional<AppInfo> application(String tenantId, String applicationId) {
            return currentApplication(tenantId, applicationId);
        }

        @Override
        public Optional<AppInfo> currentApplication(String tenantId, String applicationId) {
            if (down) throw new DirectoryUnavailable("down", null);
            return Optional.ofNullable(apps.get(applicationId));
        }

        @Override
        public String verifyReceipt(String tenantId, String applicationId, String receipt) {
            if (!receipt.equals(receipts.get(applicationId)))
                throw new ComplianceExceptions.Forbidden("Invalid application receipt");
            return apps.get(applicationId).candidateId();
        }
    }
}
