package com.openhire.identity.application;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;
import com.openhire.identity.application.port.EnterpriseDirectory;
import com.openhire.identity.application.port.IdentityRepositories.Members;
import com.openhire.identity.domain.IdentityModels.*;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import java.time.Clock;
import java.util.*;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Tenant-scoped SCIM Users profile; group/role provisioning is deliberately not supported. */
@Service
public class ScimUsers {
    public static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private final MongoTemplate mongo;
    private final EnterpriseDirectory directory;
    private final Members members;
    private final Clock clock;
    private final AuditRecorder audit;

    public ScimUsers(
            MongoTemplate mongo, EnterpriseDirectory directory, Members members, Clock clock, AuditRecorder audit) {
        this.mongo = mongo;
        this.directory = directory;
        this.members = members;
        this.clock = clock;
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.getCollection("scim_users")
                .createIndex(new Document("tenant", 1).append("userName", 1), new IndexOptions().unique(true));
        mongo.getCollection("scim_users")
                .createIndex(new Document("tenant", 1).append("externalId", 1), new IndexOptions().unique(true));
    }

    public record Input(String userName, String displayName, String externalId, boolean active) {
        public Input {
            if (userName == null || userName.length() > 254 || !userName.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                throw bad("Valid email userName required");
            userName = userName.toLowerCase(Locale.ROOT);
            if (displayName == null || displayName.isBlank() || displayName.length() > 200)
                throw bad("displayName required, maximum 200 characters");
            if (externalId == null || externalId.isBlank() || externalId.length() > 200)
                throw bad("Stable externalId required, maximum 200 characters");
        }
    }

    public static ResponseStatusException bad(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    private Document row(String tenant, String id) {
        var row = mongo.getCollection("scim_users")
                .find(and(eq("_id", id), eq("tenant", tenant), ne("deleted", true)))
                .first();
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        return row;
    }

    public Map<String, Object> get(String tenant, String id) {
        return view(row(tenant, id));
    }

    public Map<String, Object> list(String tenant, int start, int count, String filter) {
        if (start < 1 || count < 0 || count > 200 || start > 100000)
            throw bad("startIndex must be positive; count must be 0–200");
        org.bson.conversions.Bson query = and(eq("tenant", tenant), ne("deleted", true));
        if (filter != null && !filter.isBlank()) {
            var match = java.util.regex.Pattern.compile("(?i)^(userName|externalId) eq \"([^\"\\\\]{1,254})\"$")
                    .matcher(filter);
            if (!match.matches()) throw bad("Only userName eq or externalId eq filters are supported");
            String field = match.group(1).equalsIgnoreCase("userName") ? "userName" : "externalId";
            query = and(
                    query,
                    eq(field, field.equals("userName") ? match.group(2).toLowerCase(Locale.ROOT) : match.group(2)));
        }
        var collection = mongo.getCollection("scim_users");
        var rows = count == 0
                ? List.<Document>of()
                : collection
                        .find(query)
                        .sort(new Document("_id", 1))
                        .skip(start - 1)
                        .limit(count)
                        .into(new ArrayList<>());
        return Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"),
                "totalResults",
                collection.countDocuments(query),
                "startIndex",
                start,
                "itemsPerPage",
                rows.size(),
                "Resources",
                rows.stream().map(ScimUsers::view).toList());
    }

    public Map<String, Object> create(String tenant, String actor, Input input) {
        if (members.findByEmail(tenant, input.userName()).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing members cannot be adopted through SCIM");
        var row = new Document("_id", UUID.randomUUID().toString())
                .append("tenant", tenant)
                .append("userName", input.userName())
                .append("externalId", input.externalId())
                .append("displayName", input.displayName())
                .append("active", false)
                .append("version", 0L)
                .append("created", clock.instant().toString())
                .append("state", "NEW");
        try {
            mongo.getCollection("scim_users").insertOne(row);
        } catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCode() != 11000) throw duplicate;
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "userName or externalId already exists; retrieve and update it");
        }
        return update(tenant, actor, row.getString("_id"), input, null, false);
    }

    public Map<String, Object> update(
            String tenant, String actor, String id, Input input, String etag, boolean delete) {
        var current = row(tenant, id);
        if (!input.userName().equals(current.getString("userName"))
                || !input.externalId().equals(current.getString("externalId")))
            throw bad("userName and externalId are immutable");
        long version = current.getLong("version");
        if (etag != null && !etag.equals("W/\"" + version + "\""))
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "User changed");
        // No automatic expiry: an interrupted remote mutation requires operator reconciliation, not an unsafe
        // overlapping retry.
        var claim = mongo.getCollection("scim_users")
                .findOneAndUpdate(
                        and(eq("_id", id), eq("tenant", tenant), eq("version", version), ne("state", "UPDATING")),
                        new Document(
                                "$set",
                                new Document("state", "UPDATING")
                                        .append("updated", clock.instant().toString())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (claim == null)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "User is changing; an interrupted update requires reconciliation");
        try {
            String providerId = current.getString("providerId");
            if (providerId == null) providerId = directory.provision(tenant, id, input.userName(), input.displayName());
            mongo.getCollection("scim_users")
                    .updateOne(eq("_id", id), new Document("$set", new Document("providerId", providerId)));
            directory.update(tenant, providerId, input.displayName(), input.active() && !delete);
            var existing = members.findMemberById(tenant, providerId);
            // SCIM cannot grant application roles; administrators use the existing audited role-management API.
            members.save(new Member(
                    providerId,
                    tenant,
                    input.userName(),
                    input.displayName(),
                    input.active() && !delete ? MemberStatus.ACTIVE : MemberStatus.DISABLED,
                    existing.map(Member::roles).orElse(Set.of()),
                    existing.map(Member::createdAt).orElse(clock.instant())));
            audit.record(AuditRecord.of(tenant, delete ? "SCIM_USER_DEACTIVATED" : "SCIM_USER_PROVISIONED")
                    .user(actor, null)
                    .resource("USER", providerId)
                    .build());
            var updated = mongo.getCollection("scim_users")
                    .findOneAndUpdate(
                            eq("_id", id),
                            new Document(
                                            "$set",
                                            new Document("displayName", input.displayName())
                                                    .append("active", input.active() && !delete)
                                                    .append("deleted", delete)
                                                    .append("state", "READY")
                                                    .append(
                                                            "updated",
                                                            clock.instant().toString()))
                                    .append("$inc", new Document("version", 1L)),
                            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            return view(updated);
        } catch (RuntimeException error) {
            mongo.getCollection("scim_users")
                    .updateOne(eq("_id", id), new Document("$set", new Document("state", "ERROR")));
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Directory synchronization failed; retrieve this user and retry the desired update");
        }
    }

    public void delete(String tenant, String actor, String id, String etag) {
        var current = row(tenant, id);
        update(
                tenant,
                actor,
                id,
                new Input(
                        current.getString("userName"),
                        current.getString("displayName"),
                        current.getString("externalId"),
                        false),
                etag,
                true);
    }

    private static Map<String, Object> view(Document row) {
        return Map.of(
                "schemas",
                List.of(USER_SCHEMA),
                "id",
                row.getString("_id"),
                "userName",
                row.getString("userName"),
                "externalId",
                row.getString("externalId"),
                "displayName",
                row.getString("displayName"),
                "active",
                row.getBoolean("active"),
                "meta",
                Map.of(
                        "resourceType",
                        "User",
                        "created",
                        row.getString("created"),
                        "lastModified",
                        row.getString("updated") == null ? row.getString("created") : row.getString("updated"),
                        "version",
                        "W/\"" + row.getLong("version") + "\""));
    }
}
