package com.openhire.offer.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A reusable clause block that templates include by key. */
@Document("offer_clauses")
@CompoundIndex(name = "tenant_key", def = "{'tenantId': 1, 'key': 1}", unique = true)
public class OfferClause {
    @Id
    private String id;

    private String tenantId;
    private String key;
    private String title;
    private String body;
    private int version;
    private Instant updatedAt;

    protected OfferClause() {}

    public static OfferClause create(String tenantId, String key, String title, String body, Instant now) {
        var value = new OfferClause();
        value.id = tenantId + ":" + key;
        value.tenantId = tenantId;
        value.key = key;
        value.title = title;
        value.body = OfferRenderer.checked(body);
        value.version = 1;
        value.updatedAt = now;
        return value;
    }

    public void revise(String title, String body, Instant now) {
        this.title = title == null || title.isBlank() ? this.title : title.trim();
        this.body = OfferRenderer.checked(body);
        this.version++;
        this.updatedAt = now;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public int version() {
        return version;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
