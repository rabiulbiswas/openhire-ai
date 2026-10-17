package com.openhire.compliance.application;

import com.openhire.compliance.application.port.CompliancePorts.ActivityStore;
import com.openhire.compliance.application.port.CompliancePorts.AppInfo;
import com.openhire.compliance.application.port.CompliancePorts.ApplicationDirectory;
import com.openhire.compliance.application.port.CompliancePorts.DecisionStore;
import com.openhire.compliance.application.port.CompliancePorts.DirectoryUnavailable;
import com.openhire.compliance.application.port.CompliancePorts.Erased;
import com.openhire.compliance.domain.Stages;
import com.openhire.compliance.domain.Times;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Folds the application, assessment, integrity, offer and employee event streams into {@code subject_activity} (one
 * document per application: stages reached, timeline, human actions, integrity flags, offers, last activity) and
 * {@code ai_decisions} (one per assessment). The events carry ids only, so no candidate name, email or free text is
 * kept here; names are resolved at query time. Events are idempotent through the {@code seen} event-id list.
 */
public class Projections {
    static final int MAX_TIMELINE = 300;
    private static final int MAX_SEEN = 300;
    private static final int ATTEMPTS = 8;

    private final ActivityStore activity;
    private final DecisionStore decisions;
    private final Erased erased;
    private final ApplicationDirectory directory;
    private final Clock clock;

    public Projections(
            ActivityStore activity,
            DecisionStore decisions,
            Erased erased,
            ApplicationDirectory directory,
            Clock clock) {
        this.activity = activity;
        this.decisions = decisions;
        this.erased = erased;
        this.directory = directory;
        this.clock = clock;
    }

    public void accept(Map<String, Object> event) {
        var type = Maps.text(event, "eventType");
        var tenant = Maps.text(event, "tenantId");
        var eventId = Maps.text(event, "eventId");
        if (type == null || tenant == null || eventId == null) return;
        var payload = Maps.map(event.get("payload"));
        var at = Times.normalise(Maps.text(event, "occurredAt", Times.format(clock.instant())));
        var applicationId = Maps.text(payload, "applicationId");
        if (applicationId == null && "Application".equals(Maps.text(event, "aggregateType")))
            applicationId = Maps.text(event, "aggregateId");
        if (applicationId == null || !relevant(type)) return;
        if (erased.isErased(tenant, "app:" + applicationId)) return;
        var candidateId = Maps.text(payload, "candidateId");
        if (candidateId == null) candidateId = knownCandidate(tenant, applicationId);
        if (candidateId != null && erased.isErased(tenant, candidateId)) return;
        if (type.equals("assessment.completed.v1")) decision(tenant, applicationId, candidateId, payload, at);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var doc = activity.find(tenant, applicationId).orElseGet(() -> fresh(tenant, event, payload));
            var seen = new ArrayList<Object>(list(doc.get("seen")));
            if (seen.contains(eventId)) return;
            if (candidateId != null) doc.put("candidateId", candidateId);
            if (Maps.text(payload, "jobId") != null) doc.put("jobId", Maps.text(payload, "jobId"));
            fold(doc, type, Maps.text(event, "aggregateId"), payload, eventId, at);
            var last = Maps.text(doc, "lastActivityAt");
            if (last == null || last.compareTo(at) < 0) doc.put("lastActivityAt", at);
            seen.add(eventId);
            doc.put("seen", seen.subList(Math.max(0, seen.size() - MAX_SEEN), seen.size()));
            if (activity.save(doc)) return;
        }
        throw new IllegalStateException("Activity for application " + applicationId + " kept changing; will retry");
    }

    /**
     * Application events carry no candidate id, so it is looked up once, when the application is first seen. The id is
     * what lets holds, retention and erasure find the application; a failed lookup is retried on the next event.
     */
    private String knownCandidate(String tenant, String applicationId) {
        var existing = activity.find(tenant, applicationId).map(d -> Maps.text(d, "candidateId"));
        if (existing.isPresent()) return existing.get();
        try {
            return directory
                    .currentApplication(tenant, applicationId)
                    .map(AppInfo::candidateId)
                    .orElse(null);
        } catch (DirectoryUnavailable unavailable) {
            return null;
        }
    }

    private static boolean relevant(String type) {
        return type.startsWith("Application")
                || type.equals("AssessmentOverridden")
                || type.startsWith("assessment.completed")
                || type.startsWith("integrity.signal.")
                || type.startsWith("offer.")
                || type.equals("employee.created.v1");
    }

    private static Map<String, Object> fresh(String tenant, Map<String, Object> event, Map<String, Object> payload) {
        var doc = Maps.of(
                "_id",
                tenant + "|"
                        + (Maps.text(payload, "applicationId") != null
                                ? Maps.text(payload, "applicationId")
                                : Maps.text(event, "aggregateId")),
                "tenantId",
                tenant,
                "applicationId",
                Maps.text(payload, "applicationId") != null
                        ? Maps.text(payload, "applicationId")
                        : Maps.text(event, "aggregateId"),
                "version",
                0L);
        doc.put("stages", new ArrayList<>());
        doc.put("timeline", new ArrayList<>());
        doc.put("humanActions", new ArrayList<>());
        doc.put("integrity", new ArrayList<>());
        doc.put("offers", new ArrayList<>());
        doc.put("lifecycle", "ACTIVE");
        return doc;
    }

    private void fold(
            Map<String, Object> doc,
            String type,
            String aggregateId,
            Map<String, Object> payload,
            String eventId,
            String at) {
        var actorId = Maps.text(payload, "actorId");
        switch (type) {
            case "ApplicationSubmitted" -> {
                doc.put("source", Maps.text(payload, "source"));
                doc.put("submittedAt", at);
                doc.put("stage", "APPLIED");
                reach(doc, "APPLIED", at);
                timeline(doc, at, "STAGE", "Application submitted", null, "application:" + id(doc), eventId);
            }
            case "ApplicationStageChanged" -> {
                var to = Maps.text(payload, "to", "UNKNOWN");
                doc.put("stage", to);
                doc.put("lifecycle", Stages.lifecycle(to));
                reach(doc, to, at);
                timeline(
                        doc,
                        at,
                        "STAGE",
                        "Stage changed from " + Maps.text(payload, "from", "?") + " to " + to,
                        actorId,
                        "application:" + id(doc),
                        eventId);
                if (actorId != null && !actorId.equals("system") && !actorId.startsWith("system-")) {
                    var action = to.equals("SHORTLISTED") || to.equals("REJECTED") ? to : "MOVED_TO_" + to;
                    humanAction(doc, action, actorId, at, Maps.text(payload, "reason"), null);
                }
            }
            case "ApplicationDecisionUndone" -> {
                var stages = Maps.list(doc.get("stages"));
                if (stages.size() > 1) stages.removeLast();
                var back = stages.isEmpty() ? "APPLIED" : Maps.text(stages.getLast(), "stage");
                doc.put("stages", new ArrayList<>(stages));
                doc.put("stage", back);
                doc.put("lifecycle", Stages.lifecycle(back));
                timeline(doc, at, "STAGE", "Decision undone", actorId, "application:" + id(doc), eventId);
                humanAction(doc, "DECISION_UNDONE", actorId, at, null, null);
            }
            case "AssessmentOverridden" -> {
                timeline(doc, at, "STAGE", "AI score overridden", actorId, "application:" + id(doc), eventId);
                humanAction(
                        doc,
                        "SCORE_OVERRIDDEN",
                        actorId,
                        at,
                        Maps.text(payload, "reason"),
                        Maps.text(payload, "reasonCode", "OTHER"));
            }
            case "ApplicationScreened" ->
                timeline(
                        doc,
                        at,
                        "AI",
                        "Rule-based screening scored " + Maps.text(payload, "score", "?"),
                        null,
                        "application:" + id(doc),
                        eventId);
            case "ApplicationEligibilityReviewed" ->
                timeline(doc, at, "STAGE", "Eligibility reviewed", actorId, "application:" + id(doc), eventId);
            case "ApplicationNeedsAttention" ->
                timeline(
                        doc,
                        at,
                        "STAGE",
                        "Needs attention (" + Maps.text(payload, "code", "UNKNOWN") + ")",
                        null,
                        "application:" + id(doc),
                        eventId);
            case "assessment.completed.v1" ->
                timeline(
                        doc,
                        at,
                        "AI",
                        "AI assessment " + Maps.text(payload, "status", "completed") + ", score "
                                + Maps.text(payload, "score", "?"),
                        "AI",
                        "assessment:" + Maps.text(payload, "assessmentId"),
                        eventId);
            case "integrity.signal.raised.v1" -> {
                var flags = Maps.list(doc.get("integrity"));
                if (flags.stream().noneMatch(f -> Maps.text(payload, "signalId").equals(Maps.text(f, "signalId"))))
                    flags.add(Maps.of(
                            "signalId", Maps.text(payload, "signalId"),
                            "type", Maps.text(payload, "type"),
                            "severity", Maps.text(payload, "severity"),
                            "status", "RAISED",
                            "at", at));
                doc.put("integrity", new ArrayList<>(flags));
                timeline(
                        doc,
                        at,
                        "AI",
                        "Integrity flag raised: " + Maps.text(payload, "type") + " ("
                                + Maps.text(payload, "severity", "?") + ")",
                        "AI",
                        "signal:" + Maps.text(payload, "signalId"),
                        eventId);
            }
            case "integrity.signal.dismissed.v1" -> {
                for (var flag : Maps.list(doc.get("integrity")))
                    if (Maps.text(payload, "signalId").equals(Maps.text(flag, "signalId")))
                        flag.put("status", "DISMISSED");
                timeline(
                        doc,
                        at,
                        "AI",
                        "Integrity flag dismissed: " + Maps.text(payload, "type", ""),
                        null,
                        "signal:" + Maps.text(payload, "signalId"),
                        eventId);
            }
            case "employee.created.v1" -> {
                doc.put("lifecycle", "HIRED");
                doc.put("hired", true);
                timeline(
                        doc,
                        at,
                        "OFFER",
                        "Employee record created",
                        null,
                        "employee:" + Maps.text(payload, "employeeId"),
                        eventId);
            }
            default -> {
                if (type.startsWith("offer.")) offer(doc, type, payload, eventId, at);
            }
        }
    }

    private void offer(Map<String, Object> doc, String type, Map<String, Object> payload, String eventId, String at) {
        var offerId = Maps.text(payload, "offerId");
        if (offerId == null) return;
        var offers = Maps.list(doc.get("offers"));
        var existing = offers.stream()
                .filter(o -> offerId.equals(Maps.text(o, "offerId")))
                .findFirst()
                .orElseGet(() -> {
                    var created = Maps.of("offerId", offerId);
                    offers.add(created);
                    return created;
                });
        existing.put("status", Maps.text(payload, "status"));
        existing.put("at", at);
        doc.put("offers", new ArrayList<>(offers));
        var label = type.replaceFirst("^offer\\.", "")
                .replaceFirst("\\.v1$", "")
                .replace('.', ' ')
                .replace('-', ' ');
        timeline(doc, at, "OFFER", "Offer " + label, null, "offer:" + offerId, eventId);
    }

    private void decision(String tenant, String applicationId, String candidateId, Map<String, Object> p, String at) {
        var assessmentId = Maps.text(p, "assessmentId");
        if (assessmentId == null) return;
        var provenance = Maps.map(p.get("provenance"));
        var model = Maps.text(provenance, "model");
        var created = Times.normalise(Maps.text(provenance, "createdAt"));
        var criteria = new ArrayList<Map<String, Object>>();
        for (var c : Maps.list(p.get("criteria")))
            criteria.add(Maps.of(
                    "id", Maps.text(c, "id"),
                    "label", Maps.text(c, "label"),
                    "requirement", Maps.text(c, "requirement"),
                    "weight", c.get("weight"),
                    "score", c.get("score"),
                    "evidence", c.get("evidence") instanceof List<?> quotes ? new ArrayList<>(quotes) : List.of(),
                    "grounded", Boolean.TRUE.equals(c.get("grounded"))));
        var doc = Maps.of(
                "_id", tenant + "|" + assessmentId,
                "id", assessmentId,
                "tenantId", tenant,
                "applicationId", applicationId,
                "candidateId", candidateId,
                "jobId", Maps.text(p, "jobId"),
                "jobVersion", p.get("jobVersion"),
                "task", "MATCH_ASSESSMENT",
                "at", created != null ? created : at,
                "model", model,
                "promptKey", Maps.text(provenance, "promptKey"),
                "promptVersion", Maps.text(provenance, "promptVersion"),
                "rubricVersion", Maps.text(p, "rubricVersion", Maps.text(provenance, "rubricVersion")),
                "traceId", Maps.text(provenance, "traceId"),
                "status", Maps.text(p, "status"),
                "score", p.get("score"),
                "confidence", Maps.text(p, "confidence"));
        doc.put("hardGaps", p.get("hardGaps") instanceof List<?> gaps ? new ArrayList<>(gaps) : List.of());
        doc.put("criteria", criteria);
        decisions.save(doc);
    }

    private static String id(Map<String, Object> doc) {
        return Maps.text(doc, "applicationId");
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> items ? new ArrayList<>(items) : new ArrayList<>();
    }

    private static void reach(Map<String, Object> doc, String stage, String at) {
        var stages = Maps.list(doc.get("stages"));
        stages.add(Maps.of("stage", stage, "at", at));
        doc.put("stages", new ArrayList<>(stages));
    }

    private static void humanAction(
            Map<String, Object> doc, String action, String by, String at, String reason, String reasonCode) {
        var actions = Maps.list(doc.get("humanActions"));
        actions.add(Maps.of(
                "action", action,
                "by", by,
                "at", at,
                "reason", reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500),
                "reasonCode", reasonCode));
        doc.put("humanActions", new ArrayList<>(actions));
    }

    private static void timeline(
            Map<String, Object> doc, String at, String kind, String summary, String actor, String ref, String eventId) {
        var timeline = Maps.list(doc.get("timeline"));
        timeline.add(
                Maps.of("at", at, "kind", kind, "summary", summary, "actor", actor, "ref", ref, "eventId", eventId));
        if (timeline.size() > MAX_TIMELINE)
            timeline = timeline.subList(timeline.size() - MAX_TIMELINE, timeline.size());
        doc.put("timeline", new ArrayList<>(timeline));
    }
}
