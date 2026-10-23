package com.openhire.compliance.service;

import com.openhire.compliance.domain.CanonicalJson;
import com.openhire.compliance.domain.Times;
import com.openhire.compliance.domain.Ulid;
import com.openhire.compliance.exception.ComplianceExceptions;
import com.openhire.compliance.service.port.CompliancePorts.ActivityStore;
import com.openhire.compliance.service.port.CompliancePorts.ApplicationDirectory;
import com.openhire.compliance.service.port.CompliancePorts.AuditSink;
import com.openhire.compliance.service.port.CompliancePorts.Cipher;
import com.openhire.compliance.service.port.CompliancePorts.DecisionStore;
import com.openhire.compliance.service.port.CompliancePorts.Erased;
import com.openhire.compliance.service.port.CompliancePorts.Events;
import com.openhire.compliance.service.port.CompliancePorts.PrivacyStore;
import com.openhire.compliance.service.port.CompliancePorts.SelfIdentificationStore;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * The export and erasure process manager. A request is created, blocked while a legal hold applies, started by emitting
 * a compliance event, and completed when every registered participant has answered on {@code privacy.acks.v1}.
 * Acknowledgements are recorded once per participant, so redelivery is harmless.
 */
public class PrivacyService {
    public static final String ERASURE = "ERASURE";
    public static final String EXPORT = "EXPORT";
    private static final List<String> OPEN = List.of("REQUESTED", "BLOCKED_BY_LEGAL_HOLD", "IN_PROGRESS");
    private static final Pattern SECRETISH = Pattern.compile("(?i).*(hash|secret|token|password|salt|credential).*");

    private final PrivacyStore store;
    private final HoldPolicy holds;
    private final ActivityStore activity;
    private final DecisionStore decisions;
    private final SelfIdentificationStore selfIdentification;
    private final Cipher cipher;
    private final Erased erased;
    private final Events events;
    private final AuditSink sink;
    private final ApplicationDirectory directory;
    private final ComplianceSettings settings;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public PrivacyService(
            PrivacyStore store,
            HoldPolicy holds,
            ActivityStore activity,
            DecisionStore decisions,
            SelfIdentificationStore selfIdentification,
            Cipher cipher,
            Erased erased,
            Events events,
            AuditSink sink,
            ApplicationDirectory directory,
            ComplianceSettings settings,
            Clock clock) {
        this.store = store;
        this.holds = holds;
        this.activity = activity;
        this.decisions = decisions;
        this.selfIdentification = selfIdentification;
        this.cipher = cipher;
        this.erased = erased;
        this.events = events;
        this.sink = sink;
        this.directory = directory;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- creating requests ----

    /** Staff-created request; a second open request of the same type for the candidate is a conflict. */
    public Map<String, Object> createByStaff(
            String tenantId, Actor actor, String candidateId, String type, String reason) {
        var open = openRequest(tenantId, candidateId, normaliseType(type));
        if (open != null) throw new ComplianceExceptions.Conflict("An open " + type + " request already exists");
        return view(create(tenantId, candidateId, type, "STAFF", reason, actor.id(), actor.name(), null, null));
    }

    /** Request created by the retention engine. Returns null when the candidate already has an open erasure. */
    public Map<String, Object> createByRetention(
            String tenantId, String candidateId, String reason, Map<String, Object> evidence) {
        if (openRequest(tenantId, candidateId, ERASURE) != null) return null;
        return create(tenantId, candidateId, ERASURE, "RETENTION", reason, "system", null, null, evidence);
    }

    public Map<String, Object> createByCandidate(String tenantId, String applicationId, String receipt, String type) {
        var candidateId = directory.verifyReceipt(tenantId, applicationId, receipt);
        var open = openRequest(tenantId, candidateId, normaliseType(type));
        if (open != null) return candidateView(open);
        return candidateView(
                create(tenantId, candidateId, type, "CANDIDATE", null, candidateId, null, applicationId, null));
    }

    private Map<String, Object> create(
            String tenantId,
            String candidateId,
            String type,
            String source,
            String reason,
            String requestedBy,
            String requestedByName,
            String applicationId,
            Map<String, Object> evidence) {
        var kind = normaliseType(type);
        if (candidateId == null || candidateId.isBlank()) throw new IllegalArgumentException("candidateId is required");
        var cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (cleanReason != null && cleanReason.length() > 1000)
            throw new IllegalArgumentException("reason must be at most 1000 characters");
        var now = clock.instant();
        var participants = new LinkedHashMap<String, Object>();
        for (var service : settings.participants()) participants.put(service, Maps.of("status", "PENDING"));
        var request = Maps.of(
                "_id", Ulid.next("prv"),
                "tenantId", tenantId,
                "candidateId", candidateId,
                "applicationId", applicationId,
                "type", kind,
                "source", source,
                "reason", cleanReason,
                "status", "REQUESTED",
                "participants", participants,
                "createdAt", Times.format(now),
                "dueAt", Times.format(now.plus(Duration.ofDays(settings.erasureSlaDays()))),
                "stuck", false,
                "requestedBy", requestedBy);
        if (evidence != null) request.put("retention", evidence);
        var protecting =
                kind.equals(ERASURE) ? holds.protecting(tenantId, candidateId) : List.<Map<String, Object>>of();
        if (!protecting.isEmpty()) {
            request.put("status", "BLOCKED_BY_LEGAL_HOLD");
            request.put(
                    "holdIds", protecting.stream().map(h -> Maps.text(h, "_id")).toList());
        }
        store.insert(request);
        var builder = AuditRecord.of(tenantId, "PRIVACY_REQUEST_CREATED")
                .resource("PrivacyRequest", Maps.text(request, "_id"))
                .about(candidateId, null)
                .detail("type", kind)
                .detail("source", source)
                .detail("status", Maps.text(request, "status"));
        if (source.equals("CANDIDATE")) builder.candidate(candidateId);
        else if (source.equals("RETENTION")) builder.system();
        else builder.user(requestedBy, requestedByName);
        sink.record(builder.build());
        if (Maps.text(request, "status").equals("REQUESTED")) start(request);
        return store.find(tenantId, Maps.text(request, "_id")).orElse(request);
    }

    private Map<String, Object> openRequest(String tenantId, String candidateId, String type) {
        for (var request : store.list(tenantId, null, candidateId, 100))
            if (type.equals(Maps.text(request, "type")) && OPEN.contains(Maps.text(request, "status"))) return request;
        return null;
    }

    private static String normaliseType(String type) {
        var kind = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        if (!kind.equals(ERASURE) && !kind.equals(EXPORT))
            throw new IllegalArgumentException("type must be EXPORT or ERASURE");
        return kind;
    }

    private void start(Map<String, Object> request) {
        request.put("status", "IN_PROGRESS");
        request.put("startedAt", Times.format(clock.instant()));
        request.remove("holdIds");
        if (!store.replace(request, List.of("REQUESTED", "BLOCKED_BY_LEGAL_HOLD"))) return;
        var type = Maps.text(request, "type");
        events.publish(
                Maps.text(request, "tenantId"),
                "PrivacyRequest",
                Maps.text(request, "_id"),
                type.equals(ERASURE) ? "compliance.erasure.requested.v1" : "compliance.export.requested.v1",
                Maps.of(
                        "requestId", Maps.text(request, "_id"),
                        "candidateId", Maps.text(request, "candidateId"),
                        "type", type,
                        "reason", Maps.text(request, "reason"),
                        "source", Maps.text(request, "source")));
        if (settings.participants().isEmpty()) complete(request);
    }

    // ---- acknowledgements ----

    /** Consumes one event from privacy.acks.v1; true when it changed a request. */
    public boolean onAck(Map<String, Object> envelope) {
        var eventType = Maps.text(envelope, "eventType");
        boolean erasure = "privacy.erasure.completed.v1".equals(eventType);
        if (!erasure && !"privacy.export.completed.v1".equals(eventType)) return false;
        var tenant = Maps.text(envelope, "tenantId");
        var payload = Maps.map(envelope.get("payload"));
        var requestId = Maps.text(payload, "requestId");
        var service = Maps.text(payload, "service");
        if (tenant == null || requestId == null || service == null) return false;
        var request = store.find(tenant, requestId).orElse(null);
        if (request == null
                || !"IN_PROGRESS".equals(Maps.text(request, "status"))
                || !java.util.Objects.equals(Maps.text(request, "candidateId"), Maps.text(payload, "candidateId"))
                || !(erasure ? ERASURE : EXPORT).equals(Maps.text(request, "type"))
                || !Maps.map(request.get("participants")).containsKey(service)) return false;
        var status = Maps.text(payload, "status");
        if (erasure) {
            if (status == null
                    || !List.of("ERASED", "NOTHING_HELD", "RETAINED_LEGAL_BASIS")
                            .contains(status)) return false;
            if ("RETAINED_LEGAL_BASIS".equals(status)
                    && (Maps.text(payload, "note") == null
                            || Maps.text(payload, "note").isBlank())) return false;
        } else if (!"EXPORTED".equals(status) || !(payload.get("section") instanceof Map<?, ?>)) return false;
        var entry = Maps.of(
                "status",
                status,
                "counts",
                payload.get("counts") == null ? Map.of() : payload.get("counts"),
                "note",
                Maps.text(payload, "note"),
                "at",
                Times.format(clock.instant()));
        var section = erasure ? null : Maps.map(payload.get("section"));
        var updated = store.recordAck(tenant, requestId, service, entry, section);
        if (updated.isEmpty()) return false;
        var pending = Maps.map(updated.get().get("participants")).values().stream()
                .anyMatch(p -> "PENDING".equals(Maps.text(Maps.map(p), "status")));
        if (!pending) complete(updated.get());
        return true;
    }

    private void complete(Map<String, Object> request) {
        var tenant = Maps.text(request, "tenantId");
        var candidateId = Maps.text(request, "candidateId");
        var now = clock.instant();
        request.put("status", "COMPLETED");
        request.put("completedAt", Times.format(now));
        request.put("stuck", false);
        if (Maps.text(request, "type").equals(EXPORT)) {
            var expires = Times.format(now.plus(Duration.ofDays(settings.exportTtlDays())));
            request.put("exportExpiresAt", expires);
            if (!store.replace(request, List.of("IN_PROGRESS"))) return;
            var sections = new LinkedHashMap<String, Object>(Maps.map(store.export(tenant, Maps.text(request, "_id"))
                    .map(e -> e.get("sections"))
                    .orElse(Map.of())));
            sections.put("compliance-service", ownSection(tenant, candidateId));
            var export = Maps.of(
                    "generatedAt", Times.format(now),
                    "candidateId", candidateId,
                    "sections", sanitise(sections));
            store.saveExport(tenant, Maps.text(request, "_id"), export, expires);
            events.publish(
                    tenant,
                    "PrivacyRequest",
                    Maps.text(request, "_id"),
                    "compliance.export.ready.v1",
                    Maps.of("requestId", Maps.text(request, "_id"), "candidateId", candidateId, "expiresAt", expires));
        } else {
            var hash = CanonicalJson.sha256(tenant + "|" + candidateId);
            var perService = new ArrayList<Map<String, Object>>();
            Maps.map(request.get("participants")).forEach((service, value) -> {
                var p = Maps.map(value);
                perService.add(Maps.of(
                        "service", service,
                        "status", Maps.text(p, "status"),
                        "counts", p.get("counts"),
                        "note", Maps.text(p, "note"),
                        "at", Maps.text(p, "at")));
            });
            var evidence = Maps.of(
                    "requestId",
                    Maps.text(request, "_id"),
                    "candidateIdHash",
                    hash,
                    "perService",
                    perService,
                    "completedAt",
                    Maps.text(request, "completedAt"));
            var sha = CanonicalJson.sha256(CanonicalJson.write(evidence));
            evidence.put("sha256", sha);
            request.put("evidence", evidence);
            request.put("evidenceHash", sha);
            if (!store.replace(request, List.of("IN_PROGRESS"))) return;
            eraseOwnData(tenant, candidateId, Maps.text(request, "_id"));
            store.anonymise(tenant, candidateId, hash);
            events.publish(
                    tenant,
                    "PrivacyRequest",
                    Maps.text(request, "_id"),
                    "compliance.erasure.completed.v1",
                    Maps.of(
                            "requestId",
                            Maps.text(request, "_id"),
                            "candidateIdHash",
                            hash,
                            "evidenceHash",
                            sha,
                            "completedAt",
                            Maps.text(request, "completedAt")));
        }
        var builder = AuditRecord.of(tenant, "PRIVACY_REQUEST_COMPLETED")
                .system()
                .resource("PrivacyRequest", Maps.text(request, "_id"))
                .about(candidateId, null)
                .detail("type", Maps.text(request, "type"))
                .detail("source", Maps.text(request, "source"));
        sink.record(builder.build());
    }

    private void eraseOwnData(String tenant, String candidateId, String requestId) {
        var apps = activity.byCandidate(tenant, candidateId).stream()
                .map(d -> Maps.text(d, "applicationId"))
                .toList();
        erased.mark(tenant, candidateId, requestId);
        for (var app : apps) erased.mark(tenant, "app:" + app, requestId);
        activity.deleteByCandidate(tenant, candidateId);
        decisions.deleteByCandidate(tenant, candidateId);
        selfIdentification.deleteByCandidate(tenant, candidateId, apps);
    }

    /** What compliance-service itself holds about the candidate, for the export. */
    private Map<String, Object> ownSection(String tenant, String candidateId) {
        var applications = new ArrayList<Map<String, Object>>();
        var selfIds = new LinkedHashMap<String, Object>();
        var docs = activity.byCandidate(tenant, candidateId);
        for (var doc : docs) {
            applications.add(Maps.of(
                    "applicationId", Maps.text(doc, "applicationId"),
                    "jobId", Maps.text(doc, "jobId"),
                    "stage", Maps.text(doc, "stage"),
                    "submittedAt", Maps.text(doc, "submittedAt"),
                    "lastActivityAt", Maps.text(doc, "lastActivityAt"),
                    "stages", doc.get("stages")));
            selfIdentification
                    .ciphertext(tenant, Maps.text(doc, "applicationId"))
                    .ifPresent(c -> {
                        try {
                            selfIds.put(
                                    Maps.text(doc, "applicationId"),
                                    json.readValue(
                                            cipher.decrypt(c, tenant + "|" + Maps.text(doc, "applicationId")),
                                            Map.class));
                        } catch (RuntimeException unreadable) {
                            // An unreadable record is left out rather than failing the whole export.
                        }
                    });
        }
        var requests = new ArrayList<Map<String, Object>>();
        for (var r : store.list(tenant, null, candidateId, 100))
            requests.add(Maps.of(
                    "type", Maps.text(r, "type"),
                    "status", Maps.text(r, "status"),
                    "createdAt", Maps.text(r, "createdAt"),
                    "completedAt", Maps.text(r, "completedAt")));
        return Maps.of(
                "applications", applications,
                "aiDecisions", decisions.list(tenant, candidateId, null, null, null, 200),
                "selfIdentification", selfIds,
                "privacyRequests", requests);
    }

    /** Drops anything that looks like a secret or a hash, wherever it sits in a participant's section. */
    @SuppressWarnings("unchecked")
    static Object sanitise(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            ((Map<String, Object>) map).forEach((k, v) -> {
                if (!SECRETISH.matcher(k).matches()) out.put(k, sanitise(v));
            });
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(PrivacyService::sanitise).toList();
        return value;
    }

    // ---- sweeps ----

    /** Resumes erasures whose legal holds were released or expired. */
    public int resumeBlocked(String tenantId) {
        int resumed = 0;
        for (var request : store.withStatus("BLOCKED_BY_LEGAL_HOLD", 200)) {
            var tenant = Maps.text(request, "tenantId");
            if (tenantId != null && !tenantId.equals(tenant)) continue;
            if (!holds.protecting(tenant, Maps.text(request, "candidateId")).isEmpty()) continue;
            start(request);
            resumed++;
        }
        return resumed;
    }

    /** Flags requests still waiting on a participant after the configured time. */
    public int flagStuck() {
        var cutoff = Times.format(clock.instant().minus(Duration.ofHours(settings.stuckAfterHours())));
        int flagged = 0;
        for (var request : store.withStatus("IN_PROGRESS", 200)) {
            if (Boolean.TRUE.equals(request.get("stuck"))) continue;
            if (Maps.text(request, "startedAt", Maps.text(request, "createdAt")).compareTo(cutoff) > 0) continue;
            store.markStuck(Maps.text(request, "tenantId"), Maps.text(request, "_id"));
            flagged++;
        }
        return flagged;
    }

    public void sweep() {
        resumeBlocked(null);
        flagStuck();
    }

    // ---- reading ----

    public List<Map<String, Object>> list(String tenantId, String status) {
        return store.list(tenantId, status == null || status.isBlank() ? null : status, null, 200).stream()
                .map(PrivacyService::view)
                .toList();
    }

    public Map<String, Object> get(String tenantId, String id) {
        return view(
                store.find(tenantId, id).orElseThrow(() -> new ResourceNotFoundException("Privacy request not found")));
    }

    public List<Map<String, Object>> candidateRequests(String tenantId, String applicationId, String receipt) {
        var candidateId = directory.verifyReceipt(tenantId, applicationId, receipt);
        return store.list(tenantId, null, candidateId, 50).stream()
                .map(this::candidateView)
                .toList();
    }

    /** The assembled export, for the candidate who asked, until it expires. */
    public Map<String, Object> candidateExport(String tenantId, String requestId, String receipt) {
        var request =
                store.find(tenantId, requestId).orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        var applicationId = Maps.text(request, "applicationId");
        if (applicationId == null || !EXPORT.equals(Maps.text(request, "type")))
            throw new ResourceNotFoundException("Request not found");
        var candidateId = directory.verifyReceipt(tenantId, applicationId, receipt);
        if (!candidateId.equals(Maps.text(request, "candidateId")))
            throw new ComplianceExceptions.Forbidden("Invalid application receipt");
        if (!"COMPLETED".equals(Maps.text(request, "status")))
            throw new ComplianceExceptions.Conflict("The export is not ready yet");
        if (Maps.text(request, "exportExpiresAt", "").compareTo(Times.format(clock.instant())) <= 0)
            throw new ComplianceExceptions.Gone("The export has expired; request a new one");
        return store.export(tenantId, requestId)
                .orElseThrow(() -> new ComplianceExceptions.Gone("The export has expired"));
    }

    public long open(String tenantId) {
        return store.countOpen(tenantId);
    }

    public long stuck(String tenantId) {
        return store.countStuck(tenantId);
    }

    public List<Map<String, Object>> recent(String tenantId, int limit) {
        return store.list(tenantId, null, null, limit).stream()
                .map(PrivacyService::view)
                .toList();
    }

    static Map<String, Object> view(Map<String, Object> request) {
        var map = Maps.copy(request);
        map.put("id", map.remove("_id"));
        map.remove("applicationId");
        return map;
    }

    private Map<String, Object> candidateView(Map<String, Object> request) {
        var expires = Maps.text(request, "exportExpiresAt");
        boolean available = EXPORT.equals(Maps.text(request, "type"))
                && "COMPLETED".equals(Maps.text(request, "status"))
                && expires != null
                && expires.compareTo(Times.format(clock.instant())) > 0;
        return Maps.of(
                "id", Maps.text(request, "_id"),
                "type", Maps.text(request, "type"),
                "status", Maps.text(request, "status"),
                "createdAt", Maps.text(request, "createdAt"),
                "dueAt", Maps.text(request, "dueAt"),
                "completedAt", Maps.text(request, "completedAt"),
                "exportAvailable", available,
                "exportExpiresAt", expires);
    }
}
