package com.openhire.integration.adapter.out.mongo;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.openhire.integration.adapter.out.http.PinnedWebhookSender;
import com.openhire.integration.adapter.out.security.FileIntegrationSecrets;
import com.openhire.integration.application.IntegrationService;
import com.openhire.integration.domain.WebhookSecurity;
import com.openhire.support.Records;
import java.time.Clock;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebhookDeliveryQueue {
    private final MongoTemplate mongo;
    private final IntegrationService.Store configs;
    private final FileIntegrationSecrets secrets;
    private final PinnedWebhookSender sender;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;
    private final IntegrationBudget budget;

    public WebhookDeliveryQueue(
            MongoTemplate mongo,
            IntegrationService.Store configs,
            FileIntegrationSecrets secrets,
            PinnedWebhookSender sender,
            ObjectMapper json,
            Clock clock,
            @Value("${openhire.integration.webhooks-enabled:false}") boolean enabled,
            IntegrationBudget budget) {
        this.mongo = mongo;
        this.configs = configs;
        this.secrets = secrets;
        this.sender = sender;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
        this.budget = budget;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.getCollection("webhook_deliveries").createIndex(Indexes.ascending("status", "nextAttempt"));
        mongo.getCollection("webhook_deliveries").createIndex(Indexes.ascending("tenant", "subscription", "createdAt"));
    }

    public boolean enabled() {
        return enabled;
    }

    public void enqueue(String tenant, String eventId, String type, String aggregateId) {
        if (tenant == null
                || tenant.isBlank()
                || eventId == null
                || eventId.isBlank()
                || aggregateId == null
                || aggregateId.isBlank()) throw new IllegalArgumentException("Event identity required");
        // Deliberately send identifiers only, never an entire internal candidate/offer payload.
        String body = json.writeValueAsString(
                Map.of("id", eventId, "type", type, "tenantId", tenant, "resourceId", aggregateId));
        var now = Date.from(clock.instant());
        String cursor = null;
        while (true) {
            var page = configs.page(tenant, "WEBHOOK", cursor, 200);
            for (var c : page) {
                if (!c.enabled() || !c.eventTypes().contains(type)) continue;
                budget.webhook(tenant, Records.hash(c.id(), eventId));
                var d = new Document("_id", Records.hash(tenant, c.id(), eventId))
                        .append("tenant", tenant)
                        .append("subscription", c.id())
                        .append("version", c.version())
                        .append("body", body)
                        .append("status", "PENDING")
                        .append("attempts", 0)
                        .append("createdAt", now)
                        .append("nextAttempt", now)
                        .append("deadline", Date.from(clock.instant().plusSeconds(86400)));
                try {
                    mongo.getCollection("webhook_deliveries").insertOne(d);
                } catch (MongoWriteException duplicate) {
                    if (duplicate.getError().getCode() != 11000) throw duplicate;
                }
            }
            if (page.size() < 200) break;
            cursor = page.getLast().id();
        }
    }

    public List<Document> history(String tenant, String subscription) {
        return mongo.getCollection("webhook_deliveries")
                .find(and(eq("tenant", tenant), eq("subscription", subscription)))
                .projection(new Document("body", 0).append("lease", 0))
                .sort(new Document("createdAt", -1))
                .limit(100)
                .into(new ArrayList<>());
    }

    public boolean replay(String tenant, String subscription, String id) {
        return mongo.getCollection("webhook_deliveries")
                        .updateOne(
                                and(
                                        eq("_id", id),
                                        eq("tenant", tenant),
                                        eq("subscription", subscription),
                                        eq("status", "DEAD")),
                                new Document(
                                        "$set",
                                        new Document("status", "PENDING")
                                                .append("nextAttempt", Date.from(clock.instant()))
                                                .append(
                                                        "deadline",
                                                        Date.from(
                                                                clock.instant().plusSeconds(86400)))
                                                .append("attempts", 0)))
                        .getModifiedCount()
                == 1;
    }

    @Scheduled(fixedDelayString = "${openhire.integration.webhook-poll-ms:5000}")
    public void dispatch() {
        if (!enabled) return;
        var now = clock.instant();
        String lease = UUID.randomUUID().toString();
        var d = mongo.getCollection("webhook_deliveries")
                .findOneAndUpdate(
                        and(in("status", "PENDING", "SENDING"), lte("nextAttempt", Date.from(now))),
                        new Document(
                                        "$set",
                                        new Document("status", "SENDING")
                                                .append("lease", lease)
                                                .append("nextAttempt", Date.from(now.plusSeconds(120))))
                                .append("$inc", new Document("attempts", 1)),
                        new FindOneAndUpdateOptions()
                                .sort(new Document("nextAttempt", 1))
                                .returnDocument(ReturnDocument.AFTER));
        if (d == null) return;
        String status = "PENDING";
        int code = 0;
        var c = configs.find(d.getString("tenant"), "WEBHOOK", d.getString("subscription"));
        if (c.isEmpty()
                || !c.get().enabled()
                || c.get().version() != d.getLong("version")
                || !c.get().secretRef().startsWith("secret://" + d.getString("tenant") + "/")) status = "CANCELLED";
        else if (!d.getDate("deadline").toInstant().isAfter(now)) status = "DEAD";
        else {
            try {
                String encoded = json.readTree(secrets.read(c.get().secretRef()))
                        .path("signingKey")
                        .asString();
                byte[] key = Base64.getDecoder().decode(encoded);
                String id = d.getString("_id"), body = d.getString("body");
                long timestamp = now.getEpochSecond();
                code = sender.send(
                        c.get().endpoint(),
                        Map.of(
                                "X-OpenHire-Delivery",
                                id,
                                "X-OpenHire-Timestamp",
                                Long.toString(timestamp),
                                "X-OpenHire-Signature",
                                "v1=" + WebhookSecurity.sign(key, timestamp, id, body)),
                        body);
                if (code >= 200 && code < 300) status = "DELIVERED";
                else if (code >= 300 && code < 500 && code != 408 && code != 429) status = "DEAD";
            } catch (Exception failure) {
                // Never persist exceptions: provider responses and secret paths can contain credentials.
                status = "PENDING";
            }
        }
        mongo.getCollection("webhook_deliveries")
                .updateOne(
                        and(eq("_id", d.getString("_id")), eq("lease", lease)),
                        new Document(
                                "$set",
                                new Document("status", status)
                                        .append("httpStatus", code)
                                        .append("lastAttempt", Date.from(now))
                                        .append(
                                                "nextAttempt",
                                                Date.from(now.plusSeconds(
                                                        WebhookSecurity.retrySeconds(d.getInteger("attempts")))))));
    }
}
