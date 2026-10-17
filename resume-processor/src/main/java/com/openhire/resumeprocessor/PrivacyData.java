package com.openhire.resumeprocessor;

import com.openhire.support.privacy.PrivacyJson;
import com.openhire.support.privacy.PrivacyParticipant;
import com.openhire.support.privacy.Tombstones;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Export and erasure of a candidate's resumes. Resumes are stored per application, so the candidate's application
 * ids come from application-service. Erasure deletes the stored file and the whole resume record (file name, extracted
 * text and sections). The application ids are also tombstoned, so a late upload for an erased application is refused.
 * Export lists the metadata and the extracted text, never the file.
 */
@Component
class PrivacyData implements PrivacyParticipant {
    private static final String RESUMES = "resumes";

    private final MongoTemplate mongo;
    private final ApplicationLookup applications;
    private final Tombstones tombstones;
    private final Path root;

    PrivacyData(
            MongoTemplate mongo,
            ApplicationLookup applications,
            Tombstones tombstones,
            @Value("${openhire.resume.directory}") String directory) {
        this.mongo = mongo;
        this.applications = applications;
        this.tombstones = tombstones;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public String service() {
        return "resume-processor";
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var resumes = new ArrayList<Map<String, Object>>();
        for (var row : resumes(tenantId, applications.applicationIds(tenantId, candidateId))) {
            var item = new LinkedHashMap<String, Object>();
            item.put("resumeId", row.getString("_id"));
            for (var field : List.of("applicationId", "name", "mime", "status", "uploadedAt", "updatedAt"))
                if (row.get(field) != null) item.put(field, PrivacyJson.safe(row.get(field), java.util.Set.of()));
            item.put("sizeBytes", size(row.getString("_id")));
            item.put("extractedText", row.getString("text") == null ? "" : row.getString("text"));
            resumes.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("resumes", resumes);
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        var ids = applications.applicationIds(tenantId, candidateId);
        int files = 0;
        var rows = resumes(tenantId, ids);
        for (var row : rows) if (deleteFile(row.getString("_id"))) files++;
        int records = ids.isEmpty()
                ? 0
                : (int) mongo.remove(
                                Query.query(Criteria.where("tenantId")
                                        .is(tenantId)
                                        .and("applicationId")
                                        .in(ids)),
                                RESUMES)
                        .getDeletedCount();
        ids.forEach(id -> tombstones.mark(tenantId, id, requestId));
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("resumes", records);
        counts.put("files", files);
        return Outcome.erased(counts);
    }

    private List<Document> resumes(String tenantId, List<String> applicationIds) {
        if (applicationIds.isEmpty()) return List.of();
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("applicationId")
                        .in(applicationIds)),
                Document.class,
                RESUMES);
    }

    private long size(String id) {
        try {
            return Files.exists(root.resolve(id)) ? Files.size(root.resolve(id)) : 0;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private boolean deleteFile(String id) {
        try {
            return Files.deleteIfExists(root.resolve(id));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not delete a stored resume file", failure);
        }
    }
}
