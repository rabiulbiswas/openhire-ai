package com.openhire.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class Records {
    private Records() {}

    public static String now() {
        return Instant.now().toString();
    }

    public static String hash(String... parts) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] value = part.getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
                hash.update(value);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String text(Map<String, ?> value, String key) {
        var item = value.get(key);
        return item == null ? "" : item.toString();
    }

    public static String required(Map<String, ?> value, String key, int max) {
        var text = text(value, key).trim();
        if (text.isBlank() || text.length() > max)
            throw new IllegalArgumentException(key + " is required (max " + max + ")");
        return text;
    }

    public static Document doc(Object value) {
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> m) {
            var d = new Document();
            m.forEach((k, v) -> d.put(k.toString(), v));
            return d;
        }
        return new Document();
    }

    public static List<Document> documents(Object value) {
        return value instanceof List<?> list ? list.stream().map(Records::doc).toList() : List.of();
    }

    public static Document event(String type, String tenant, String aggregate, String id, Map<String, ?> payload) {
        return new Document("eventId", UUID.randomUUID().toString())
                .append("producer", "phase3-workflow")
                .append("eventType", type)
                .append("eventVersion", 1)
                .append("occurredAt", now())
                .append("tenantId", tenant)
                .append("aggregateType", aggregate)
                .append("aggregateId", id)
                .append("correlationId", id)
                .append("payload", payload);
    }

    public static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
    }

    public static ResponseStatusException conflict(String text) {
        return new ResponseStatusException(HttpStatus.CONFLICT, text);
    }
}
