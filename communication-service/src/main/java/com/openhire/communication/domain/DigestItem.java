package com.openhire.communication.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** An email notification held back for a staff member who chose an hourly or daily digest. */
@Document("digest_items")
@CompoundIndex(name = "tenant_subject", def = "{'tenantId': 1, 'subject': 1, 'createdAt': 1}")
public class DigestItem {
    @Id
    private String id;

    private String tenantId;
    private String subject;
    private String title;
    private String body;
    private Instant createdAt;

    protected DigestItem() {}

    public static DigestItem create(String tenantId, String subject, String title, String body, Instant now) {
        var value = new DigestItem();
        value.id = "dig_" + UUID.randomUUID().toString().replace("-", "");
        value.tenantId = tenantId;
        value.subject = subject;
        value.title = title;
        value.body = body;
        value.createdAt = now;
        return value;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }
}
