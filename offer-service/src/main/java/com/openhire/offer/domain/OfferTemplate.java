package com.openhire.offer.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A versioned offer letter template. The body is plain text with {{merge}} fields and a {{clauses}} slot. */
@Document("offer_templates")
@CompoundIndex(name = "tenant_key", def = "{'tenantId': 1, 'key': 1}", unique = true)
public class OfferTemplate {
    @Id
    private String id;

    private String tenantId;
    private String key;
    private String name;
    private String country;
    private String level;
    private int version;
    private String body;
    private List<String> clauseKeys;
    private Instant updatedAt;
    private String updatedBy;

    protected OfferTemplate() {}

    public static OfferTemplate create(
            String tenantId,
            String key,
            String name,
            String country,
            String level,
            String body,
            List<String> clauseKeys,
            String by,
            Instant now) {
        var value = new OfferTemplate();
        value.id = tenantId + ":" + key;
        value.tenantId = tenantId;
        value.key = key;
        value.name = name;
        value.country = country;
        value.level = level;
        value.version = 1;
        value.body = OfferRenderer.checked(body);
        value.clauseKeys = List.copyOf(clauseKeys);
        value.updatedAt = now;
        value.updatedBy = by;
        return value;
    }

    public void revise(String name, String body, List<String> clauseKeys, String by, Instant now) {
        this.name = name == null || name.isBlank() ? this.name : name.trim();
        this.body = OfferRenderer.checked(body);
        this.clauseKeys = List.copyOf(clauseKeys);
        this.version++;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    public String tenantId() {
        return tenantId;
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public String country() {
        return country;
    }

    public String level() {
        return level;
    }

    public int version() {
        return version;
    }

    public String body() {
        return body;
    }

    public List<String> clauseKeys() {
        return clauseKeys;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
