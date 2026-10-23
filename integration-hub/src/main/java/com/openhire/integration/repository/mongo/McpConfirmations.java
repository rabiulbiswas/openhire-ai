package com.openhire.integration.repository.mongo;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;
import com.openhire.integration.service.IntegrationService;
import com.openhire.support.Records;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class McpConfirmations {
    private final MongoTemplate mongo;
    private final Clock clock;
    private final IntegrationService service;
    private final IntegrationService.Store configs;

    public McpConfirmations(
            MongoTemplate mongo, Clock clock, IntegrationService service, IntegrationService.Store configs) {
        this.mongo = mongo;
        this.clock = clock;
        this.service = service;
        this.configs = configs;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.getCollection("mcp_confirmations")
                .createIndex(new Document("expiresAt", 1), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    public String prepare(String tenant, String actor, String connector) {
        var c = configs.find(tenant, "CONNECTOR", connector).orElseThrow(Records::missing);
        String id = UUID.randomUUID().toString();
        mongo.getCollection("mcp_confirmations")
                .insertOne(new Document("_id", id)
                        .append("tenant", tenant)
                        .append("actor", actor)
                        .append("connector", connector)
                        .append("name", c.name())
                        .append("version", c.version())
                        .append("operation", "DISABLE_CONNECTOR")
                        .append("state", "PENDING")
                        .append("expiresAt", Date.from(clock.instant().plusSeconds(300))));
        return id;
    }

    public List<Document> pending(String tenant, String actor) {
        return mongo.getCollection("mcp_confirmations")
                .find(and(
                        eq("tenant", tenant),
                        eq("actor", actor),
                        eq("state", "PENDING"),
                        gt("expiresAt", Date.from(clock.instant()))))
                .projection(new Document("tenant", 0).append("actor", 0))
                .limit(100)
                .into(new ArrayList<>());
    }

    public void decide(String tenant, String actor, String id, boolean approve) {
        long changed = mongo.getCollection("mcp_confirmations")
                .updateOne(
                        and(
                                eq("_id", id),
                                eq("tenant", tenant),
                                eq("actor", actor),
                                eq("state", "PENDING"),
                                gt("expiresAt", Date.from(clock.instant()))),
                        new Document("$set", new Document("state", approve ? "APPROVED" : "REJECTED")))
                .getModifiedCount();
        if (changed != 1) throw Records.conflict("Confirmation expired or already decided");
    }

    public void execute(String tenant, String actor, String connector, String confirmation) {
        var accepted = mongo.getCollection("mcp_confirmations")
                .findOneAndUpdate(
                        and(
                                eq("_id", confirmation),
                                eq("tenant", tenant),
                                eq("actor", actor),
                                eq("connector", connector),
                                eq("state", "APPROVED"),
                                gt("expiresAt", Date.from(clock.instant()))),
                        new Document("$set", new Document("state", "EXECUTING")),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (accepted == null) throw Records.conflict("Explicit human approval is required or was already consumed");
        try {
            var c = configs.find(tenant, "CONNECTOR", connector).orElseThrow(Records::missing);
            if (c.version() != accepted.getLong("version"))
                throw Records.conflict("Connector changed since approval; request new confirmation");
            service.save(
                    tenant,
                    actor,
                    "CONNECTOR",
                    connector,
                    c.version(),
                    c.name(),
                    c.provider(),
                    c.endpoint(),
                    c.secretRef(),
                    c.eventTypes(),
                    false);
            mongo.getCollection("mcp_confirmations")
                    .updateOne(eq("_id", confirmation), new Document("$set", new Document("state", "EXECUTED")));
        } catch (RuntimeException failed) {
            mongo.getCollection("mcp_confirmations")
                    .updateOne(eq("_id", confirmation), new Document("$set", new Document("state", "FAILED")));
            throw failed;
        }
    }
}
