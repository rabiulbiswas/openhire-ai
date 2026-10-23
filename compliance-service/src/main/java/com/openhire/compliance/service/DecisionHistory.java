package com.openhire.compliance.service;

import com.openhire.compliance.domain.AuditEntry;
import com.openhire.compliance.domain.Times;
import com.openhire.compliance.service.port.CompliancePorts.ActivityStore;
import com.openhire.compliance.service.port.CompliancePorts.ApplicationDirectory;
import com.openhire.compliance.service.port.CompliancePorts.AuditFilter;
import com.openhire.compliance.service.port.CompliancePorts.AuditStore;
import com.openhire.compliance.service.port.CompliancePorts.DecisionStore;
import com.openhire.compliance.service.port.CompliancePorts.PrivacyStore;
import com.openhire.platform.web.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** AI decision history and the decision trace. Candidate names are looked up when asked, never stored here. */
public class DecisionHistory {
    private final DecisionStore decisions;
    private final ActivityStore activity;
    private final AuditStore audit;
    private final PrivacyStore privacy;
    private final ApplicationDirectory directory;

    public DecisionHistory(
            DecisionStore decisions,
            ActivityStore activity,
            AuditStore audit,
            PrivacyStore privacy,
            ApplicationDirectory directory) {
        this.decisions = decisions;
        this.activity = activity;
        this.audit = audit;
        this.privacy = privacy;
        this.directory = directory;
    }

    public List<Map<String, Object>> list(
            String tenantId, String candidateId, String applicationId, String task, String from, int limit) {
        var rows = decisions.list(
                tenantId, candidateId, applicationId, task, Times.normalise(from), Math.max(1, Math.min(limit, 200)));
        var out = new ArrayList<Map<String, Object>>();
        for (var row : rows) out.add(summary(tenantId, row, true));
        return out;
    }

    public Map<String, Object> get(String tenantId, String id) {
        var row =
                decisions.find(tenantId, id).orElseThrow(() -> new ResourceNotFoundException("AI decision not found"));
        var view = summary(tenantId, row, true);
        view.put("jobId", row.get("jobId"));
        view.put("criteria", row.get("criteria"));
        view.put("hardGaps", row.get("hardGaps"));
        var doc = activity.find(tenantId, Maps.text(row, "applicationId"));
        view.put("integrityFlags", doc.map(d -> Maps.list(d.get("integrity"))).orElse(List.of()));
        view.put("humanActions", doc.map(d -> after(d, Maps.text(row, "at"))).orElse(List.of()));
        return view;
    }

    private Map<String, Object> summary(String tenantId, Map<String, Object> row, boolean withName) {
        var applicationId = Maps.text(row, "applicationId");
        var view = Maps.of(
                "id", Maps.text(row, "id"),
                "applicationId", applicationId,
                "candidateId", Maps.text(row, "candidateId"),
                "candidateName", withName ? name(tenantId, applicationId) : null,
                "task", Maps.text(row, "task"),
                "at", Maps.text(row, "at"),
                "model", Maps.text(row, "model"),
                "promptVersion", Maps.text(row, "promptVersion"),
                "rubricVersion", Maps.text(row, "rubricVersion"));
        view.put(
                "outcome",
                Maps.of(
                        "status", Maps.text(row, "status"),
                        "score", row.get("score"),
                        "confidence", Maps.text(row, "confidence")));
        var latest = activity.find(tenantId, applicationId)
                .map(d -> after(d, Maps.text(row, "at")))
                .orElse(List.of());
        if (!latest.isEmpty()) view.put("humanAction", latest.getLast());
        return view;
    }

    private static List<Map<String, Object>> after(Map<String, Object> doc, String at) {
        return Maps.list(doc.get("humanActions")).stream()
                .filter(a -> at == null || Maps.text(a, "at", "").compareTo(at) >= 0)
                .toList();
    }

    private String name(String tenantId, String applicationId) {
        try {
            return directory
                    .application(tenantId, applicationId)
                    .map(a -> a.candidateName())
                    .filter(n -> n != null && !n.isBlank())
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** One ordered timeline for an application: audit records, AI decisions, stage and offer events, privacy. */
    public List<Map<String, Object>> trace(String tenantId, String applicationId) {
        var entries = new ArrayList<Map<String, Object>>();
        for (var record : audit.page(tenantId, AuditFilter.none().forApplication(applicationId), null, 500))
            entries.add(auditEntry(record));
        for (var row : decisions.list(tenantId, null, applicationId, null, null, 100)) {
            var summary = "AI assessment " + Maps.text(row, "status", "") + ": score " + row.get("score")
                    + ", confidence " + Maps.text(row, "confidence", "?") + " (model " + Maps.text(row, "model", "?")
                    + ", prompt " + Maps.text(row, "promptVersion", "?") + ", rubric "
                    + Maps.text(row, "rubricVersion", "?") + ")";
            entries.add(Maps.of(
                    "at",
                    Maps.text(row, "at"),
                    "kind",
                    "AI",
                    "summary",
                    summary,
                    "actor",
                    "AI",
                    "ref",
                    "ai-decision:" + Maps.text(row, "id")));
        }
        var doc = activity.find(tenantId, applicationId);
        doc.ifPresent(d -> {
            var assessmentRefs = decisions.list(tenantId, null, applicationId, null, null, 100).stream()
                    .map(r -> "assessment:" + Maps.text(r, "id"))
                    .toList();
            for (var item : Maps.list(d.get("timeline"))) {
                var kind = Maps.text(item, "kind");
                // Assessments already appear as full AI decisions above.
                if (assessmentRefs.contains(Maps.text(item, "ref"))) continue;
                entries.add(Maps.of(
                        "at", Maps.text(item, "at"),
                        "kind", kind,
                        "summary", Maps.text(item, "summary"),
                        "actor", Maps.text(item, "actor"),
                        "ref", Maps.text(item, "ref")));
            }
            var candidateId = Maps.text(d, "candidateId");
            if (candidateId != null)
                for (var request : privacy.list(tenantId, null, candidateId, 50))
                    entries.add(Maps.of(
                            "at",
                            Maps.text(request, "createdAt"),
                            "kind",
                            "PRIVACY",
                            "summary",
                            "Privacy request " + Maps.text(request, "type") + " "
                                    + Maps.text(request, "status", "").toLowerCase(java.util.Locale.ROOT),
                            "actor",
                            Maps.text(request, "requestedBy", Maps.text(request, "source")),
                            "ref",
                            "privacy-request:" + Maps.text(request, "id")));
        });
        entries.sort(Comparator.comparing((Map<String, Object> e) -> Maps.text(e, "at", "")));
        return entries;
    }

    private static Map<String, Object> auditEntry(AuditEntry record) {
        var actor = record.actor();
        var who = Maps.text(actor, "name") != null ? Maps.text(actor, "name") : Maps.text(actor, "id");
        return Maps.of(
                "at",
                record.at(),
                "kind",
                record.action().startsWith("PRIVACY") ? "PRIVACY" : "AUDIT",
                "summary",
                record.action().replace('_', ' ').toLowerCase(java.util.Locale.ROOT) + " ("
                        + Maps.text(record.resource(), "type") + ")",
                "actor",
                who,
                "ref",
                "audit:" + record.id());
    }
}
