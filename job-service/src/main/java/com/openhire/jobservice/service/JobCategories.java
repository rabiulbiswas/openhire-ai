package com.openhire.jobservice.service;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.client.model.IndexOptions;
import com.openhire.jobservice.domain.Job;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class JobCategories {
    private final MongoTemplate mongo;
    private final AuditRecorder audit;

    public JobCategories(MongoTemplate mongo, AuditRecorder audit) {
        this.mongo = mongo;
        this.audit = audit;
    }

    public record Category(String id, String name, boolean active, long revision) {}

    public record Page(List<Category> data, long total, int page, int size) {}

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void indexes() {
        mongo.getCollection("job_categories")
                .createIndex(
                        new Document("tenantId", 1).append("normalizedName", 1),
                        new IndexOptions().unique(true).partialFilterExpression(new Document("deleted", false)));
    }

    public Page list(String tenant, boolean activeOnly, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100)
            throw new IllegalArgumentException("Invalid pagination");
        var filter = activeOnly
                ? and(eq("tenantId", tenant), eq("active", true), ne("deleted", true))
                : and(eq("tenantId", tenant), ne("deleted", true));
        var collection = mongo.getCollection("job_categories");
        var data = new ArrayList<Category>();
        for (var row : collection
                .find(filter)
                .sort(new Document("normalizedName", 1).append("_id", 1))
                .skip(page * size)
                .limit(size)) data.add(view(row));
        return new Page(data, collection.countDocuments(filter), page, size);
    }

    public Category save(String tenant, String actor, String id, String name, boolean active, long revision) {
        name = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Category name must be 1 to 80 characters without control characters");
        var collection = mongo.getCollection("job_categories");
        var row = new Document("_id", id == null ? UUID.randomUUID().toString() : id)
                .append("tenantId", tenant)
                .append("name", name)
                .append("normalizedName", name.toLowerCase(Locale.ROOT))
                .append("active", active)
                .append("revision", revision + 1)
                .append("deleted", false);
        try {
            if (id == null) {
                if (revision != 0) throw new IllegalArgumentException("New categories start at revision zero");
                collection.insertOne(row);
            } else if (collection
                            .replaceOne(
                                    and(
                                            eq("_id", id),
                                            eq("tenantId", tenant),
                                            ne("deleted", true),
                                            eq("revision", revision)),
                                    row)
                            .getModifiedCount()
                    != 1) throw new IllegalStateException("Category changed or was removed; reload before saving");
        } catch (com.mongodb.MongoWriteException conflict) {
            if (conflict.getError().getCode() == 11000)
                throw new IllegalStateException("A category with this name already exists");
            throw conflict;
        }
        audit.record(AuditRecord.of(tenant, "JOB_CATEGORY_SAVED")
                .user(actor, null)
                .resource("JobCategory", row.getString("_id"))
                .build());
        return view(row);
    }

    public void delete(String tenant, String actor, String id, long revision) {
        boolean used = mongo.getCollection("jobs")
                        .countDocuments(and(eq("tenantId", tenant), eq("versions.classification.categoryId", id)))
                > 0;
        // Tombstones retain identity and names for immutable job/approval snapshots, including concurrent references.
        if (mongo.getCollection("job_categories")
                        .updateOne(
                                and(
                                        eq("_id", id),
                                        eq("tenantId", tenant),
                                        eq("revision", revision),
                                        ne("deleted", true)),
                                new Document("$set", new Document("active", false).append("deleted", !used))
                                        .append("$inc", new Document("revision", 1L)))
                        .getModifiedCount()
                != 1) throw new IllegalStateException("Category changed or was removed; reload before deleting");
        audit.record(AuditRecord.of(tenant, "JOB_CATEGORY_REMOVED")
                .user(actor, null)
                .resource("JobCategory", id)
                .build());
    }

    public Job.Classification resolve(
            String tenant, String categoryId, String mode, Job.Classification previous, boolean required) {
        if ((categoryId == null || categoryId.isBlank()) && (mode == null || mode.isBlank())) {
            if (required) throw new IllegalArgumentException("Job category and work mode are required for new jobs");
            return previous;
        }
        String id = categoryId == null && previous != null ? previous.categoryId() : categoryId;
        String workMode = mode == null && previous != null ? previous.workMode() : mode;
        if (id == null
                || id.isBlank()
                || workMode == null
                || !Set.of("REMOTE", "ONSITE", "HYBRID").contains(workMode))
            throw new IllegalArgumentException(
                    "Choose a job category and a valid work mode (Remote, Onsite or Hybrid)");
        var row = mongo.getCollection("job_categories")
                .find(and(eq("_id", id), eq("tenantId", tenant)))
                .first();
        if (row == null) throw new ResourceNotFoundException("Job category was not found");
        boolean unchanged = previous != null && id.equals(previous.categoryId());
        if ((!row.getBoolean("active", false) || row.getBoolean("deleted", false)) && !unchanged)
            throw new IllegalArgumentException("Choose an active job category");
        return new Job.Classification(id, unchanged ? previous.categoryName() : row.getString("name"), workMode);
    }

    private Category view(Document row) {
        return new Category(
                row.getString("_id"),
                row.getString("name"),
                row.getBoolean("active", false),
                ((Number) row.get("revision")).longValue());
    }
}
