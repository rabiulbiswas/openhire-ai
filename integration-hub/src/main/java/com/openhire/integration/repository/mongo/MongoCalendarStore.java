package com.openhire.integration.repository.mongo;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.openhire.integration.security.TokenCipher;
import com.openhire.integration.service.CalendarPorts.*;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class MongoCalendarStore implements Store {
    private final MongoTemplate mongo;
    private final TokenCipher cipher;
    private final ObjectMapper json;

    MongoCalendarStore(MongoTemplate mongo, TokenCipher cipher, ObjectMapper json) {
        this.mongo = mongo;
        this.cipher = cipher;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.getCollection("oauth_pending")
                .createIndex(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
        mongo.getCollection("oauth_grants")
                .createIndex(Indexes.ascending("tenant", "connector"), new IndexOptions().unique(true));
    }

    public void pending(Pending p) {
        mongo.getCollection("oauth_pending")
                .insertOne(new Document("_id", p.state())
                        .append("tenant", p.tenant())
                        .append("actor", p.actor())
                        .append("connector", p.connector())
                        .append("version", p.version())
                        .append("grantRevision", p.grantRevision())
                        .append("expiresAt", Date.from(p.expiresAt()))
                        .append("verifier", cipher.encrypt(p.tenant() + ":" + p.state(), p.verifier())));
    }

    public Pending consume(String state, String tenant, String actor, Instant now) {
        var d = mongo.getCollection("oauth_pending")
                .findOneAndDelete(and(
                        eq("_id", state), eq("tenant", tenant), eq("actor", actor), gt("expiresAt", Date.from(now))));
        return d == null
                ? null
                : new Pending(
                        state,
                        tenant,
                        actor,
                        d.getString("connector"),
                        d.getLong("version"),
                        d.getLong("grantRevision"),
                        cipher.decrypt(tenant + ":" + state, d.getString("verifier")),
                        d.getDate("expiresAt").toInstant());
    }

    public SavedGrant load(String tenant, String connector) {
        var d = mongo.getCollection("oauth_grants")
                .find(and(eq("tenant", tenant), eq("connector", connector)))
                .first();
        if (d == null) return null;
        return new SavedGrant(
                tenant,
                connector,
                d.getLong("configVersion"),
                d.getLong("revision"),
                Boolean.TRUE.equals(d.getBoolean("disconnected"))
                        ? null
                        : json.readValue(
                                cipher.decrypt(context(tenant, connector), d.getString("encrypted")), Grant.class));
    }

    private static String context(String tenant, String connector) {
        return tenant.length() + ":" + tenant + ":" + connector;
    }

    public boolean save(SavedGrant g, long expected) {
        var filter = and(eq("tenant", g.tenant()), eq("connector", g.connector()));
        var d = new Document("tenant", g.tenant())
                .append("connector", g.connector())
                .append("configVersion", g.configVersion())
                .append("revision", g.revision())
                .append(
                        "encrypted",
                        cipher.encrypt(context(g.tenant(), g.connector()), json.writeValueAsString(g.grant())));
        if (expected == 0) {
            try {
                mongo.getCollection("oauth_grants").insertOne(d);
                return true;
            } catch (MongoWriteException duplicate) {
                if (duplicate.getError().getCode() == 11000) return false;
                throw duplicate;
            }
        }
        return mongo.getCollection("oauth_grants")
                        .replaceOne(and(filter, eq("revision", expected)), d)
                        .getModifiedCount()
                == 1;
    }

    public void disconnect(String tenant, String connector) {
        mongo.getCollection("oauth_pending").deleteMany(and(eq("tenant", tenant), eq("connector", connector)));
        mongo.getCollection("oauth_grants")
                .updateOne(
                        and(eq("tenant", tenant), eq("connector", connector)),
                        new Document("$set", new Document("disconnected", true))
                                .append("$setOnInsert", new Document("configVersion", 0L))
                                .append("$inc", new Document("revision", 1L))
                                .append("$unset", new Document("encrypted", "")),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
    }
}
