package com.openhire.platform.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One consequential action, for the append-only audit trail. Details are for reason codes and counts, never
 * personal data: keys that look like names, contact details or free text are dropped.
 */
public record AuditRecord(
        String tenantId,
        String action,
        ActorType actorType,
        String actorId,
        String actorName,
        String resourceType,
        String resourceId,
        String candidateId,
        String applicationId,
        Map<String, Object> details,
        Instant at) {
    public enum ActorType {
        USER,
        CANDIDATE,
        SYSTEM,
        AI
    }

    private static final Pattern PERSONAL =
            Pattern.compile("(?i).*(email|phone|name|resume|text|address|note|message).*");
    private static final int MAX_DETAIL_CHARS = 2000;

    public AuditRecord {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("Audit records need a tenant");
        if (action == null || !action.matches("[A-Z][A-Z0-9_]{2,63}"))
            throw new IllegalArgumentException("Audit action must be an upper-case code such as OFFER_SENT");
        if (resourceType == null || resourceType.isBlank() || resourceId == null || resourceId.isBlank())
            throw new IllegalArgumentException("Audit records need the resource they concern");
        actorType = actorType == null ? ActorType.SYSTEM : actorType;
        actorId = actorId == null || actorId.isBlank() ? "system" : actorId;
        at = at == null ? Instant.now() : at;
        var clean = new LinkedHashMap<String, Object>();
        if (details != null)
            details.forEach((key, value) -> {
                if (value == null || PERSONAL.matcher(key).matches()) return;
                clean.put(key, value instanceof Number || value instanceof Boolean ? value : String.valueOf(value));
            });
        if (clean.toString().length() > MAX_DETAIL_CHARS)
            throw new IllegalArgumentException("Audit details are too long");
        details = Map.copyOf(clean);
    }

    public static Builder of(String tenantId, String action) {
        return new Builder(tenantId, action);
    }

    public static final class Builder {
        private final String tenantId;
        private final String action;
        private ActorType actorType = ActorType.USER;
        private String actorId;
        private String actorName;
        private String resourceType;
        private String resourceId;
        private String candidateId;
        private String applicationId;
        private final Map<String, Object> details = new LinkedHashMap<>();

        private Builder(String tenantId, String action) {
            this.tenantId = tenantId;
            this.action = action;
        }

        public Builder actor(ActorType type, String id, String name) {
            this.actorType = type;
            this.actorId = id;
            this.actorName = name;
            return this;
        }

        public Builder user(String id, String name) {
            return actor(ActorType.USER, id, name);
        }

        public Builder candidate(String candidateId) {
            this.actorType = ActorType.CANDIDATE;
            this.actorId = candidateId;
            this.candidateId = candidateId;
            return this;
        }

        public Builder system() {
            return actor(ActorType.SYSTEM, "system", null);
        }

        public Builder resource(String type, String id) {
            this.resourceType = type;
            this.resourceId = id;
            return this;
        }

        /** The candidate the action concerns, so a decision can be traced from the candidate's side. */
        public Builder about(String candidateId, String applicationId) {
            if (candidateId != null && !candidateId.isBlank()) this.candidateId = candidateId;
            this.applicationId = applicationId;
            return this;
        }

        public Builder detail(String key, Object value) {
            details.put(key, value);
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(
                    tenantId,
                    action,
                    actorType,
                    actorId,
                    actorName,
                    resourceType,
                    resourceId,
                    candidateId,
                    applicationId,
                    details,
                    Instant.now());
        }
    }
}
