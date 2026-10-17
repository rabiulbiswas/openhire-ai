package com.openhire.compliance.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** One link of a tenant's audit chain. {@code at} is a fixed-width UTC timestamp, so it sorts as text. */
public record AuditEntry(
        String id,
        String tenantId,
        long seq,
        String eventId,
        String action,
        Map<String, Object> actor,
        Map<String, Object> resource,
        String candidateId,
        String applicationId,
        Map<String, Object> details,
        String source,
        String at,
        String prevHash,
        String hash) {

    /** The record as hashed: every field except {@code hash}. */
    public Map<String, Object> hashable() {
        var map = new LinkedHashMap<String, Object>();
        map.put("_id", id);
        map.put("tenantId", tenantId);
        map.put("seq", seq);
        map.put("eventId", eventId);
        map.put("action", action);
        map.put("actor", actor);
        map.put("resource", resource);
        if (candidateId != null) map.put("candidateId", candidateId);
        if (applicationId != null) map.put("applicationId", applicationId);
        map.put("details", details);
        map.put("source", source);
        map.put("at", at);
        map.put("prevHash", prevHash);
        return map;
    }

    public Map<String, Object> toMap() {
        var map = hashable();
        map.put("hash", hash);
        return map;
    }

    /** The API and export shape: {@code id} instead of {@code _id}. */
    public Map<String, Object> view() {
        var map = toMap();
        map.remove("_id");
        var view = new LinkedHashMap<String, Object>();
        view.put("id", id);
        view.putAll(map);
        return view;
    }

    @SuppressWarnings("unchecked")
    public static AuditEntry fromMap(Map<String, Object> map) {
        return new AuditEntry(
                (String) map.get("_id"),
                (String) map.get("tenantId"),
                ((Number) map.get("seq")).longValue(),
                (String) map.get("eventId"),
                (String) map.get("action"),
                (Map<String, Object>) map.get("actor"),
                (Map<String, Object>) map.get("resource"),
                (String) map.get("candidateId"),
                (String) map.get("applicationId"),
                map.get("details") == null ? Map.of() : (Map<String, Object>) map.get("details"),
                (String) map.get("source"),
                (String) map.get("at"),
                (String) map.get("prevHash"),
                (String) map.get("hash"));
    }
}
