package com.openhire.integration.repository.mongo;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.openhire.integration.domain.CalendarEvent;
import com.openhire.integration.service.CalendarConnections;
import com.openhire.integration.service.IntegrationService;
import com.openhire.support.Records;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Durable desired-state reconciliation. New changes cannot overwrite an in-flight provider receipt. */
@Component
public class CalendarWorkQueue {
    private static final String COLLECTION = "calendar_work";
    private final MongoTemplate mongo;
    private final IntegrationService.Store configs;
    private final CalendarConnections calendars;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;
    private final IntegrationBudget budget;

    public CalendarWorkQueue(
            MongoTemplate mongo,
            IntegrationService.Store configs,
            CalendarConnections calendars,
            ObjectMapper json,
            Clock clock,
            @Value("${openhire.integration.calendar-writes-enabled:false}") boolean enabled,
            IntegrationBudget budget) {
        this.mongo = mongo;
        this.configs = configs;
        this.calendars = calendars;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
        this.budget = budget;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.getCollection(COLLECTION).createIndex(new Document("status", 1).append("nextAttempt", 1));
        mongo.getCollection(COLLECTION).createIndex(new Document("tenant", 1).append("connector", 1));
    }

    public Document enqueue(
            String tenant,
            String connector,
            String resource,
            String requestId,
            Long version,
            CalendarEvent event,
            boolean cancel,
            Instant sourceTime) {
        if (resource == null
                || !resource.matches("[A-Za-z0-9_-]{1,200}")
                || requestId == null
                || !requestId.matches("[A-Za-z0-9_-]{8,200}"))
            throw new IllegalArgumentException("Stable resource ID and idempotency key required");
        var c = configs.find(tenant, "CONNECTOR", connector).orElseThrow(Records::missing);
        if (!c.enabled()) throw new IllegalArgumentException("Connector disabled");
        if (!cancel && event == null) throw new IllegalArgumentException("Event required");
        String id = Records.hash(tenant, connector, resource);
        var collection = mongo.getCollection(COLLECTION);
        var old = collection.find(eq("_id", id)).first();
        String body = cancel ? "{}" : json.writeValueAsString(event);
        String hash = Records.hash(body, Boolean.toString(cancel));
        if (old != null && requestId.equals(old.getString("requestId"))) {
            if (!hash.equals(old.getString("requestHash")))
                throw Records.conflict("Idempotency key reused with different event data");
            return view(old);
        }
        if (old != null
                && sourceTime != null
                && old.getString("sourceTime") != null
                && sourceTime.isBefore(Instant.parse(old.getString("sourceTime")))) return view(old);
        long prior = old == null ? 0 : old.getLong("version");
        if (sourceTime == null && (version == null || version != prior))
            throw Records.conflict("Event version changed; reload before saving");
        if (old != null && old.getLong("configVersion") != c.version() && old.getString("remoteId") != null)
            throw Records.conflict(
                    "Connector configuration changed; reconcile its existing remote calendar event before continuing");
        budget.calendar(tenant, Records.hash(connector, resource, requestId));
        var next = old == null
                ? new Document("_id", id)
                        .append("tenant", tenant)
                        .append("connector", connector)
                        .append("resource", resource)
                : new Document(old);
        if (old == null) next.append("creationKey", Records.hash(id, "initial"));
        next.append("version", prior + 1)
                .append("configVersion", c.version())
                .append("requestId", requestId)
                .append("requestHash", hash)
                .append("event", body)
                .append("cancel", cancel)
                .append("attempts", 0)
                .append("deadline", Date.from(clock.instant().plusSeconds(86400)))
                .append("stamp", UUID.randomUUID().toString());
        if (sourceTime != null) next.append("sourceTime", sourceTime.toString());
        if (old == null || !"RUNNING".equals(old.getString("status")))
            next.append("status", "PENDING").append("nextAttempt", Date.from(clock.instant()));
        if (old == null) {
            try {
                collection.insertOne(next);
            } catch (MongoWriteException duplicate) {
                if (duplicate.getError().getCode() == 11000)
                    throw Records.conflict("Concurrent event creation; retry with the same key");
                throw duplicate;
            }
        } else if (collection
                        .replaceOne(and(eq("_id", id), eq("stamp", old.getString("stamp"))), next)
                        .getModifiedCount()
                != 1) throw Records.conflict("Event changed concurrently; retry with the same key");
        return view(next);
    }

    public Document get(String tenant, String connector, String resource) {
        var record = mongo.getCollection(COLLECTION)
                .find(eq("_id", Records.hash(tenant, connector, resource)))
                .first();
        if (record == null) throw Records.missing();
        return view(record);
    }

    private static Document view(Document record) {
        return new Document("resourceId", record.getString("resource"))
                .append("version", record.getLong("version"))
                .append("status", record.getString("status"))
                .append("attempts", record.getInteger("attempts", 0))
                .append("remoteId", record.getString("remoteId"))
                .append("lastError", record.getString("lastError"));
    }

    @Scheduled(fixedDelayString = "${openhire.integration.calendar-poll-ms:5000}")
    public void dispatch() {
        if (!enabled) return;
        String lease = UUID.randomUUID().toString();
        var now = clock.instant();
        var collection = mongo.getCollection(COLLECTION);
        var job = collection.findOneAndUpdate(
                and(in("status", "PENDING", "RUNNING"), lte("nextAttempt", Date.from(now))),
                new Document(
                                "$set",
                                new Document("status", "RUNNING")
                                        .append("lease", lease)
                                        .append("stamp", UUID.randomUUID().toString())
                                        .append("nextAttempt", Date.from(now.plusSeconds(120))))
                        .append("$inc", new Document("attempts", 1)),
                new FindOneAndUpdateOptions()
                        .sort(new Document("nextAttempt", 1))
                        .returnDocument(ReturnDocument.AFTER));
        if (job == null) return;
        String outcome = "PENDING", error = "PROVIDER_RETRY_REQUIRED", remote = job.getString("remoteId");
        var config = configs.find(job.getString("tenant"), "CONNECTOR", job.getString("connector"));
        if (config.isEmpty() || !config.get().enabled() || config.get().version() != job.getLong("configVersion")) {
            outcome = "FAILED";
            error = "CONNECTOR_CHANGED";
        } else if (!job.getDate("deadline").toInstant().isAfter(now)) {
            outcome = "FAILED";
            error = "RETRY_WINDOW_EXPIRED";
        } else {
            try {
                boolean cancel = job.getBoolean("cancel");
                var result = calendars.write(
                        job.getString("tenant"),
                        job.getString("connector"),
                        job.getString("creationKey"),
                        remote,
                        cancel ? null : json.readValue(job.getString("event"), CalendarEvent.class),
                        cancel);
                if (cancel) remote = null;
                else if (result.get("id") instanceof String id && !id.isBlank()) remote = id;
                else throw new IllegalStateException("Missing provider receipt");
                outcome = cancel ? "CANCELLED" : "SYNCED";
                error = null;
            } catch (RuntimeException unavailable) {
                /* Persist a safe error code, never tokens or provider response text. */
            }
        }
        boolean success = outcome.equals("SYNCED") || outcome.equals("CANCELLED");
        var updates = new Document(
                        "status",
                        new Document(
                                "$cond",
                                List.of(
                                        new Document("$eq", List.of("$version", job.getLong("version"))),
                                        outcome,
                                        "PENDING")))
                .append("lastError", error)
                .append("stamp", UUID.randomUUID().toString())
                .append(
                        "nextAttempt",
                        Date.from(now.plusSeconds(
                                success
                                        ? 0
                                        : com.openhire.integration.domain.WebhookSecurity.retrySeconds(
                                                job.getInteger("attempts")))));
        if (success) updates.append("remoteId", remote == null ? null : new Document("$literal", remote));
        if (outcome.equals("CANCELLED"))
            updates.append(
                    "creationKey",
                    Records.hash(job.getString("_id"), "cancelled", Long.toString(job.getLong("version"))));
        collection.updateOne(
                and(eq("_id", job.getString("_id")), eq("lease", lease)), List.of(new Document("$set", updates)));
    }
}
