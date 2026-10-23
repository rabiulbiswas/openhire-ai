package com.openhire.candidateservice.service;

import static com.openhire.support.Records.*;

import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import java.util.*;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Identity resolution, provenance and resumable merge saga; all queries are tenant scoped. */
@Service
public class CandidateService {
    private final MongoTemplate mongo;
    private final AuditRecorder audit;

    public CandidateService(MongoTemplate mongo, AuditRecorder audit) {
        this.mongo = mongo;
        this.audit = audit;
    }

    private Query key(String tenant, String id) {
        return Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(id));
    }

    public Document canonical(String tenant, String id) {
        for (int depth = 0; depth < 16; depth++) {
            var row = mongo.findOne(key(tenant, id), Document.class, "candidates");
            if (row == null) throw missing();
            if (!text(row, "mergedInto").isEmpty()) {
                id = text(row, "mergedInto");
                continue;
            }
            return row;
        }
        throw conflict("Candidate merge chain requires review");
    }

    public Document resolve(Document request) {
        String tenant = required(request, "tenantId", 100),
                email = required(request, "email", 254).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Invalid email");
        String id = hash(tenant, email), name = required(request, "name", 200);
        var provenance = new Document("source", text(request, "source"))
                .append("applicationId", text(request, "applicationId"))
                .append("recordedAt", now());
        var row = new Document("_id", id)
                .append("tenantId", tenant)
                .append("name", name)
                .append("normalizedName", name.toLowerCase(Locale.ROOT))
                .append("email", email)
                .append("phone", text(request, "phone"))
                .append("title", text(request, "title"))
                .append("location", text(request, "location"))
                .append("owner", text(request, "owner"))
                .append("inOpenHireSince", now())
                .append("applications", new Document())
                .append("documents", new Document())
                .append("timeline", new Document())
                .append(
                        "provenance",
                        new Document("name", provenance)
                                .append("email", provenance)
                                .append("phone", provenance))
                .append(
                        "consents",
                        new Document("processing", true)
                                .append("talentPool", false)
                                .append("marketing", false))
                .append("skills", List.of())
                .append("experience", List.of())
                .append(
                        "_events",
                        List.of(event(
                                "CandidateCreated",
                                tenant,
                                "Candidate",
                                id,
                                Map.of("source", text(request, "source")))));
        try {
            mongo.insert(row, "candidates");
        } catch (DuplicateKeyException ignored) {
        }
        return new Document("id", canonical(tenant, id).getString("_id"));
    }

    public Document project(Document request) {
        String tenant = required(request, "tenantId", 100),
                id = required(request, "candidateId", 100),
                app = required(request, "applicationId", 100);
        if (!app.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid application ID");
        var current = canonical(tenant, id);
        id = current.getString("_id");
        // An erased candidate stays erased: a late application event must not rebuild the profile.
        if (Boolean.TRUE.equals(current.get("erased"))) return new Document("id", id);
        if (current.containsKey("mergeLock")) throw conflict("Candidate merge is in progress; retry");
        long revision = ((Number) request.getOrDefault("revision", 0)).longValue();
        var application = new Document("applicationId", app)
                .append("jobId", text(request, "jobId"))
                .append("jobTitle", text(request, "jobTitle"))
                .append("stage", text(request, "stage"))
                .append("appliedAt", text(request, "appliedAt"))
                .append("revision", revision);
        var query = key(tenant, id);
        query.addCriteria(
                Criteria.where("mergeLock").exists(false).and("mergedInto").exists(false));
        query.addCriteria(new Criteria()
                .orOperator(
                        Criteria.where("applications." + app + ".revision").exists(false),
                        Criteria.where("applications." + app + ".revision").lt(revision)));
        var update = new Update()
                .set("applications." + app, application)
                .set(
                        "timeline." + app + "_" + revision,
                        new Document("id", app + "_" + revision)
                                .append("title", text(request, "stage"))
                                .append("detail", text(request, "jobTitle") + " · " + text(request, "updatedAt"))
                                .append("done", true));
        if (!text(request, "resumeId").isBlank())
            update.set(
                    "documents." + text(request, "resumeId"),
                    new Document("id", text(request, "resumeId"))
                            .append("name", text(request, "resumeName"))
                            .append("uploadedAt", text(request, "updatedAt")));
        update.push(
                "_events",
                event(
                        "CandidateApplicationUpdated",
                        tenant,
                        "Candidate",
                        id,
                        Map.of("applicationId", app, "revision", revision)));
        var result = mongo.updateFirst(query, update, "candidates");
        if (result.getMatchedCount() == 0) {
            var latest = canonical(tenant, id);
            if (latest.containsKey("mergeLock") || !id.equals(latest.getString("_id")))
                throw conflict("Candidate changed during projection; retry");
        }
        return new Document("id", id);
    }

    public Document view(String tenant, String id) {
        var row = new Document(canonical(tenant, id));
        row.put("id", row.remove("_id"));
        for (String field : List.of("applications", "documents", "timeline"))
            row.put(field, new ArrayList<>(doc(row.get(field)).values()));
        row.remove("_events");
        row.remove("mergeLock");
        row.remove("normalizedName");
        row.remove("tenantId");
        return row;
    }

    public void owner(String tenant, String id, String owner) {
        id = canonical(tenant, id).getString("_id");
        var query = key(tenant, id);
        query.addCriteria(
                Criteria.where("mergeLock").exists(false).and("mergedInto").exists(false));
        if (mongo.updateFirst(query, new Update().set("owner", owner), "candidates")
                        .getMatchedCount()
                == 0) throw conflict("Merge is in progress");
    }

    public List<Document> suggestions(String tenant) {
        var result = new ArrayList<Document>();
        var active = Query.query(Criteria.where("tenantId")
                        .is(tenant)
                        .and("mergedInto")
                        .exists(false)
                        .and("erased")
                        .exists(false))
                .with(org.springframework.data.domain.Sort.by("_id"));
        // Stream candidates instead of limiting identity review to the first 500 records.
        try (var candidates = mongo.stream(active, Document.class, "candidates")) {
            var iterator = candidates.iterator();
            while (iterator.hasNext()) {
                var one = iterator.next();
                var matches = new ArrayList<Criteria>();
                matches.add(Criteria.where("normalizedName").is(text(one, "normalizedName")));
                if (!text(one, "phone").isBlank())
                    matches.add(Criteria.where("phone").is(text(one, "phone")));
                var query = Query.query(Criteria.where("tenantId")
                                .is(tenant)
                                .and("mergedInto")
                                .exists(false)
                                .and("erased")
                                .exists(false)
                                .and("_id")
                                .gt(one.getString("_id")))
                        .addCriteria(new Criteria().orOperator(matches.toArray(Criteria[]::new)));
                try (var others = mongo.stream(query, Document.class, "candidates")) {
                    var peers = others.iterator();
                    while (peers.hasNext()) {
                        var two = peers.next();
                        String sid = hash(tenant, one.getString("_id"), two.getString("_id"));
                        if (mongo.exists(key(tenant, sid), "merge_dismissals")) continue;
                        var suggestion = new Document("_id", sid)
                                .append("tenantId", tenant)
                                .append("a", one.getString("_id"))
                                .append("b", two.getString("_id"));
                        try {
                            mongo.insert(suggestion, "merge_suggestions");
                        } catch (DuplicateKeyException ignored) {
                        }
                        var fields = new ArrayList<Document>();
                        for (String field : List.of("name", "email", "phone", "title", "location", "owner"))
                            fields.add(new Document("key", field)
                                    .append("label", field)
                                    .append("same", text(one, field).equals(text(two, field)))
                                    .append("valueA", text(one, field))
                                    .append("valueB", text(two, field)));
                        boolean phone = !text(one, "phone").isBlank()
                                && text(one, "phone").equals(text(two, "phone"));
                        result.add(new Document("id", sid)
                                .append("nameA", text(one, "name"))
                                .append("nameB", text(two, "name"))
                                .append(
                                        "reason",
                                        phone ? "Matching phone" : "Matching name; review evidence before merging")
                                .append("confidencePhrase", phone ? "Matching contact detail" : "Possible match")
                                .append("fields", fields));
                        if (result.size() == 100) return result;
                    }
                }
            }
        }
        return result;
    }

    public void dismiss(String tenant, String id) {
        if (!mongo.exists(key(tenant, id), "merge_suggestions")) throw missing();
        mongo.upsert(
                key(tenant, id), new Update().set("tenantId", tenant).set("dismissedAt", now()), "merge_dismissals");
    }

    public Document merge(String tenant, String actor, String suggestionId, Document keep) {
        var suggestion = mongo.findOne(key(tenant, suggestionId), Document.class, "merge_suggestions");
        if (suggestion == null) throw missing();
        for (var entry : keep.entrySet())
            if (!Set.of("name", "email", "phone", "title", "location", "owner").contains(entry.getKey())
                    || !Set.of("a", "b").contains(entry.getValue()))
                throw new IllegalArgumentException("Invalid merge field selection");
        var operation = new Document("_id", suggestionId)
                .append("tenantId", tenant)
                .append("actor", actor)
                .append("a", suggestion.getString("a"))
                .append("b", suggestion.getString("b"))
                .append("keep", keep)
                .append("status", "PENDING");
        try {
            mongo.insert(operation, "candidate_merges");
        } catch (DuplicateKeyException ignored) {
            operation = mongo.findOne(key(tenant, suggestionId), Document.class, "candidate_merges");
            if (!doc(operation.get("keep")).equals(keep))
                throw conflict("Merge already requested with different selections");
        }
        finishMerge(operation);
        return new Document("id", suggestionId)
                .append(
                        "status",
                        text(mongo.findOne(key(tenant, suggestionId), Document.class, "candidate_merges"), "status"));
    }

    @Scheduled(fixedDelay = 3000)
    public void recoverMerges() {
        for (var row : mongo.find(
                Query.query(Criteria.where("status").is("PENDING")).limit(20), Document.class, "candidate_merges")) {
            try {
                finishMerge(row);
            } catch (org.springframework.dao.DataAccessException ignored) {
                /* Durable operation retries on next poll. */
            }
        }
        for (var row : mongo.find(
                Query.query(Criteria.where("status")
                                .in("DONE", "CONFLICT")
                                .and("locksReleased")
                                .ne(true))
                        .limit(20),
                Document.class,
                "candidate_merges")) {
            releaseLocks(row);
        }
    }

    private void releaseLocks(Document op) {
        for (String id : List.of(op.getString("a"), op.getString("b")))
            mongo.updateFirst(
                    key(op.getString("tenantId"), id)
                            .addCriteria(Criteria.where("mergeLock").is(op.getString("_id"))),
                    new Update().unset("mergeLock"),
                    "candidates");
        mongo.updateFirst(
                key(op.getString("tenantId"), op.getString("_id")),
                new Update().set("locksReleased", true),
                "candidate_merges");
    }

    private void finishMerge(Document op) {
        if (!"PENDING".equals(op.getString("status"))) return;
        String tenant = op.getString("tenantId"),
                operation = op.getString("_id"),
                a = op.getString("a"),
                b = op.getString("b");
        var claim = key(tenant, operation)
                .addCriteria(Criteria.where("status").is("PENDING"))
                .addCriteria(new Criteria()
                        .orOperator(
                                Criteria.where("runUntil").exists(false),
                                Criteria.where("runUntil").lt(now())));
        if (mongo.findAndModify(
                        claim,
                        new Update()
                                .set(
                                        "runUntil",
                                        java.time.Instant.now().plusSeconds(300).toString()),
                        FindAndModifyOptions.options().returnNew(true),
                        Document.class,
                        "candidate_merges")
                == null) return;
        var locked = new ArrayList<String>();
        for (String id : List.of(a, b)) {
            var query = key(tenant, id);
            query.addCriteria(new Criteria()
                    .orOperator(
                            Criteria.where("mergeLock").exists(false),
                            Criteria.where("mergeLock").is(operation)));
            var row = mongo.findAndModify(
                    query,
                    new Update().set("mergeLock", operation),
                    FindAndModifyOptions.options().returnNew(true),
                    Document.class,
                    "candidates");
            if (row == null) {
                for (String lock : locked)
                    mongo.updateFirst(
                            key(tenant, lock)
                                    .addCriteria(Criteria.where("mergeLock").is(operation)),
                            new Update().unset("mergeLock"),
                            "candidates");
                mongo.updateFirst(key(tenant, operation), new Update().unset("runUntil"), "candidate_merges");
                return;
            }
            locked.add(id);
        }
        var target = mongo.findOne(key(tenant, a), Document.class, "candidates");
        var source = mongo.findOne(key(tenant, b), Document.class, "candidates");
        if (target.containsKey("mergedInto")
                || (source.containsKey("mergedInto") && !a.equals(source.getString("mergedInto")))) {
            mongo.updateFirst(key(tenant, operation), new Update().set("status", "CONFLICT"), "candidate_merges");
        } else {
            var update = new Update();
            var selections = doc(op.get("keep"));
            for (var field : selections.keySet())
                if ("b".equals(selections.getString(field))) {
                    update.set(field, text(source, field));
                    update.set(
                            "provenance." + field, doc(source.get("provenance")).get(field));
                    if ("name".equals(field))
                        update.set("normalizedName", text(source, field).toLowerCase(Locale.ROOT));
                }
            for (String field : List.of("applications", "documents", "timeline"))
                for (var entry : doc(source.get(field)).entrySet()) {
                    if (!doc(target.get(field)).containsKey(entry.getKey()))
                        update.set(field + "." + entry.getKey(), entry.getValue());
                }
            update.set(
                    "timeline.merge_" + operation,
                    new Document("id", operation)
                            .append("title", "Candidate records merged")
                            .append("detail", "Reviewed by " + op.getString("actor"))
                            .append("done", true));
            mongo.updateFirst(key(tenant, a), update, "candidates");
            mongo.updateFirst(key(tenant, b), new Update().set("mergedInto", a), "candidates");
            // Event ID equals operation ID, so recovery is idempotent even if acknowledgement races.
            var event = event(
                    "CandidateMerged",
                    tenant,
                    "Candidate",
                    a,
                    Map.of("sourceCandidateId", b, "survivorCandidateId", a));
            event.put("eventId", operation);
            mongo.updateFirst(key(tenant, a), new Update().addToSet("_events", event), "candidates");
            mongo.updateFirst(key(tenant, operation), new Update().set("status", "DONE"), "candidate_merges");
            audit.record(AuditRecord.of(tenant, "CANDIDATE_MERGED")
                    .user(op.getString("actor"), null)
                    .resource("Candidate", a)
                    .about(a, null)
                    .detail("survivorId", a)
                    .detail("sourceId", b)
                    .build());
        }
        releaseLocks(op);
    }
}
