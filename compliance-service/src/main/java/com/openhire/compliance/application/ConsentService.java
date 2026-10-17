package com.openhire.compliance.application;

import com.openhire.compliance.application.port.CompliancePorts.AuditFilter;
import com.openhire.compliance.application.port.CompliancePorts.AuditSink;
import com.openhire.compliance.application.port.CompliancePorts.AuditStore;
import com.openhire.compliance.application.port.CompliancePorts.ConsentStore;
import com.openhire.compliance.domain.Times;
import com.openhire.compliance.domain.Ulid;
import com.openhire.platform.audit.AuditRecord;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Versioned consent texts and the consent records derived from audit events. */
public class ConsentService {
    public static final String DEFAULT_VERSION = "privacy-v1";
    public static final String DEFAULT_TEXT =
            "I agree to the processing of my application and resume for this role. A person makes hiring decisions.";

    private final ConsentStore store;
    private final AuditSink sink;
    private final AuditStore audit;
    private final Clock clock;

    public ConsentService(ConsentStore store, AuditSink sink, AuditStore audit, Clock clock) {
        this.store = store;
        this.sink = sink;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Map<String, Object>> texts(String tenantId) {
        return store.texts(tenantId).stream().map(ConsentService::view).toList();
    }

    public Map<String, Object> publish(String tenantId, Actor actor, String purpose, String locale, String text) {
        var cleanPurpose = purpose == null || purpose.isBlank()
                ? "PROCESSING"
                : purpose.trim().toUpperCase(Locale.ROOT);
        if (!cleanPurpose.matches("[A-Z][A-Z0-9_]{1,39}")) throw new IllegalArgumentException("purpose is invalid");
        var cleanText = text == null ? "" : text.trim();
        if (cleanText.length() < 20 || cleanText.length() > 4000)
            throw new IllegalArgumentException("text must be 20 to 4,000 characters");
        var cleanLocale = locale == null || locale.isBlank() ? "en" : locale.trim();
        int next = store.texts(tenantId).stream()
                        .filter(t -> cleanPurpose.equals(Maps.text(t, "purpose")))
                        .mapToInt(t -> ((Number) t.get("number")).intValue())
                        .max()
                        .orElse(1)
                + 1;
        var prefix = cleanPurpose.equals("PROCESSING") ? "privacy" : cleanPurpose.toLowerCase(Locale.ROOT);
        var doc = Maps.of(
                "_id",
                Ulid.next("cns"),
                "tenantId",
                tenantId,
                "purpose",
                cleanPurpose,
                "number",
                next,
                "version",
                prefix + "-v" + next,
                "locale",
                cleanLocale,
                "text",
                cleanText,
                "status",
                "ACTIVE",
                "publishedBy",
                actor.id(),
                "publishedAt",
                Times.format(clock.instant()));
        store.publish(doc);
        sink.record(AuditRecord.of(tenantId, "CONSENT_TEXT_PUBLISHED")
                .user(actor.id(), actor.name())
                .resource("ConsentText", Maps.text(doc, "_id"))
                .detail("purpose", cleanPurpose)
                .detail("version", Maps.text(doc, "version"))
                .build());
        return view(doc);
    }

    /** The active text for the purpose, or the built-in default when the tenant has published none. */
    public Map<String, Object> active(String tenantId, String purpose) {
        var cleanPurpose = purpose == null || purpose.isBlank() ? "PROCESSING" : purpose.toUpperCase(Locale.ROOT);
        var found = store.active(tenantId, cleanPurpose);
        if (found.isPresent()) {
            var view = view(found.get());
            view.put("builtIn", false);
            return view;
        }
        return Maps.of(
                "purpose",
                cleanPurpose,
                "version",
                DEFAULT_VERSION,
                "locale",
                "en",
                "text",
                DEFAULT_TEXT,
                "status",
                "ACTIVE",
                "builtIn",
                true);
    }

    public String activeVersion(String tenantId) {
        return Maps.text(active(tenantId, "PROCESSING"), "version");
    }

    /** Consents given or revoked by a candidate, from the audit trail. */
    public List<Map<String, Object>> records(String tenantId, String candidateId) {
        if (candidateId == null || candidateId.isBlank()) throw new IllegalArgumentException("candidateId is required");
        var out = new ArrayList<Map<String, Object>>();
        for (var entry : audit.page(tenantId, AuditFilter.none().forCandidate(candidateId), null, 500)) {
            var action = entry.action();
            if (!action.contains("CONSENT") || action.equals("CONSENT_TEXT_PUBLISHED")) continue;
            var revoked = action.contains("REVOKED") || action.contains("WITHDRAWN");
            out.add(Maps.of(
                    "candidateId", candidateId,
                    "purpose", Maps.text(entry.details(), "purpose", "PROCESSING"),
                    "version", Maps.text(entry.details(), "version", Maps.text(entry.details(), "consentVersion")),
                    "status", revoked ? "REVOKED" : "GRANTED",
                    "at", entry.at()));
        }
        return out;
    }

    private static Map<String, Object> view(Map<String, Object> doc) {
        return Maps.of(
                "id", Maps.text(doc, "_id"),
                "version", Maps.text(doc, "version"),
                "purpose", Maps.text(doc, "purpose"),
                "locale", Maps.text(doc, "locale"),
                "text", Maps.text(doc, "text"),
                "status", Maps.text(doc, "status"),
                "publishedBy", Maps.text(doc, "publishedBy"),
                "publishedAt", Maps.text(doc, "publishedAt"));
    }
}
