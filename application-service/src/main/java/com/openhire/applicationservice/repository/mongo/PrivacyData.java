package com.openhire.applicationservice.repository.mongo;

import static com.openhire.support.Records.doc;
import static com.openhire.support.Records.documents;
import static com.openhire.support.Records.now;
import static com.openhire.support.Records.text;

import com.openhire.support.privacy.PrivacyJson;
import com.openhire.support.privacy.PrivacyParticipant;
import com.openhire.support.privacy.Tombstones;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Export and erasure of a candidate's applications.
 *
 * <p>Erasure anonymises each submission in place instead of deleting it: the name becomes "Erased candidate" and the
 * email, phone, answers, resume file name and excerpt, free-text decision reasons, assessment evidence quotes and
 * integrity flag texts are removed. Ids, job, source, stage, timestamps and numeric scores stay, so pipeline analytics
 * and fairness reports stay consistent. The row keeps its candidate id, which is also how the other services find the
 * application ids of an erased candidate. Bulk-import rows that staged the candidate's CSV line are scrubbed too.
 */
@Component
public class PrivacyData implements PrivacyParticipant {
    static final String ERASED_NAME = "Erased candidate";
    private static final String SUBMISSIONS = "submissions";
    private static final String IMPORT_ROWS = "import_rows";

    private final MongoTemplate mongo;
    private final Tombstones tombstones;

    public PrivacyData(MongoTemplate mongo, Tombstones tombstones) {
        this.mongo = mongo;
        this.tombstones = tombstones;
    }

    @Override
    public String service() {
        return "application-service";
    }

    /** Application ids of the candidate, still resolvable after erasure. */
    public List<String> applicationIds(String tenantId, String candidateId) {
        return submissions(tenantId, candidateId).stream()
                .map(row -> row.getString("_id"))
                .toList();
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var applications = new ArrayList<Map<String, Object>>();
        for (var row : submissions(tenantId, candidateId)) {
            if (row.containsKey("erasedAt")) continue;
            var item = new LinkedHashMap<String, Object>();
            item.put("applicationId", row.getString("_id"));
            for (var field : List.of(
                    "jobId",
                    "jobTitle",
                    "source",
                    "stage",
                    "submittedAt",
                    "updatedAt",
                    "name",
                    "email",
                    "phone",
                    "externalReference",
                    "resumeName",
                    "consentVersion",
                    "processingConsent",
                    "score",
                    "eligibilityApproved"))
                if (row.get(field) != null && !text(row, field).isBlank()) item.put(field, row.get(field));
            item.put("answers", PrivacyJson.map(doc(row.get("answers"))));
            if (row.containsKey("assessment"))
                item.put("assessment", PrivacyJson.map(doc(row.get("assessment")), "provenance", "overridden"));
            var overridden = doc(doc(row.get("assessment")).get("overridden"));
            if (!overridden.isEmpty())
                item.put(
                        "scoreOverride",
                        PrivacyJson.map(new Document("newScore", overridden.get("newScore"))
                                .append("reason", overridden.get("reason"))
                                .append("reasonCode", overridden.get("reasonCode"))));
            if (row.containsKey("hireDecision")) {
                var decision = doc(row.get("hireDecision"));
                item.put(
                        "hireDecision",
                        PrivacyJson.map(new Document("decision", decision.get("decision"))
                                .append("reason", decision.get("reason"))
                                .append("at", decision.get("at"))));
            }
            item.put(
                    "decisions",
                    documents(row.get("decisions")).stream()
                            .map(entry -> PrivacyJson.map(entry, "actor"))
                            .toList());
            item.put("integrityFlags", PrivacyJson.maps(documents(row.get("integrityFlags"))));
            applications.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("applications", applications);
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        int anonymised = 0;
        var ids = new ArrayList<String>();
        for (var row : submissions(tenantId, candidateId)) {
            ids.add(row.getString("_id"));
            if (row.containsKey("erasedAt")) continue;
            mongo.updateFirst(
                    Query.query(
                            Criteria.where("tenantId").is(tenantId).and("_id").is(row.getString("_id"))),
                    anonymise(row),
                    SUBMISSIONS);
            anonymised++;
        }
        int staged = ids.isEmpty() ? 0 : scrubImportRows(tenantId, ids);
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("applications", anonymised);
        counts.put("importRows", staged);
        return Outcome.erased(counts);
    }

    private List<Document> submissions(String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("candidateId")
                        .is(candidateId)),
                Document.class,
                SUBMISSIONS);
    }

    private static Update anonymise(Document row) {
        var update = new Update()
                .set("name", ERASED_NAME)
                .set("answers", new Document())
                .set("fingerprint", "erased")
                .set("projectionDirty", false)
                // A terminal state keeps the screening loop from reading the resume again.
                .set("processingState", "COMPLETE")
                .set("erasedAt", now())
                .inc("revision", 1)
                .unset("email")
                .unset("phone")
                .unset("externalReference")
                .unset("resumeName")
                .unset("detail")
                .unset("lease")
                .unset("matchingCommands")
                .unset("integrityFlag")
                .unset("hireDecision.reason");
        var decisions = new ArrayList<Document>();
        for (var decision : documents(row.get("decisions"))) {
            var copy = new Document(decision);
            if (copy.containsKey("reason")) copy.put("reason", "");
            decisions.add(copy);
        }
        if (row.containsKey("decisions")) update.set("decisions", decisions);
        if (row.containsKey("integrityFlags")) update.set("integrityFlags", blankReasons(row.get("integrityFlags")));
        if (row.containsKey("assessment")) update.set("assessment", assessment(doc(row.get("assessment"))));
        return update;
    }

    private static Document assessment(Document source) {
        var assessment = new Document(source);
        for (var field : List.of("resumeExcerpt", "candidateName", "integrityFlag")) assessment.remove(field);
        for (var field : List.of("strengths", "gapsToProbe", "hardGaps"))
            if (assessment.containsKey(field)) assessment.put(field, List.of());
        var criteria = new ArrayList<Document>();
        for (var criterion : documents(source.get("criteria"))) {
            var copy = new Document(criterion);
            if (copy.containsKey("evidence")) copy.put("evidence", List.of());
            criteria.add(copy);
        }
        if (source.containsKey("criteria")) assessment.put("criteria", criteria);
        if (source.containsKey("integrityFlags"))
            assessment.put("integrityFlags", blankReasons(source.get("integrityFlags")));
        if (assessment.get("overridden") != null) {
            var overridden = new Document(doc(assessment.get("overridden")));
            overridden.remove("reason");
            assessment.put("overridden", overridden);
        }
        return assessment;
    }

    private static List<Document> blankReasons(Object flags) {
        var result = new ArrayList<Document>();
        for (var flag : documents(flags)) {
            var copy = new Document(flag);
            if (copy.containsKey("reason")) copy.put("reason", "");
            result.add(copy);
        }
        return result;
    }

    private int scrubImportRows(String tenantId, List<String> applicationIds) {
        var holding = Criteria.where("tenantId")
                .is(tenantId)
                .and("applicationId")
                .in(applicationIds)
                .orOperator(
                        Criteria.where("input.email").exists(true),
                        Criteria.where("input.name").exists(true),
                        Criteria.where("file").exists(true));
        var rows = mongo.find(Query.query(holding), Document.class, IMPORT_ROWS).size();
        if (rows > 0)
            mongo.updateMulti(
                    Query.query(holding),
                    new Update()
                            .unset("input.name")
                            .unset("input.email")
                            .unset("input.phone")
                            .unset("input.resume")
                            .unset("file"),
                    IMPORT_ROWS);
        return rows;
    }
}
