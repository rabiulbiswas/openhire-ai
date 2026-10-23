package com.openhire.employee.repository.mongo;

import static com.openhire.support.Records.documents;

import com.openhire.support.privacy.PrivacyJson;
import com.openhire.support.privacy.PrivacyParticipant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Export and erasure for a candidate who was hired. The employee record is an employment record: it is retained on a
 * legal basis and nothing in it is deleted (the answer is RETAINED_LEGAL_BASIS). Applicant-stage data was erased by
 * the other services. Hire sagas that have created their employee only hold copies of the candidate's name and contact
 * details; those copies are removed. A saga that has not created the employee yet is left alone, because the employee
 * record is built from it. Referral rewards are part of the retained employment record. No tombstone is written: hiring
 * events for an accepted offer must keep flowing.
 */
@Component
class PrivacyData implements PrivacyParticipant {
    static final String ERASED_NAME = "Erased candidate";
    static final String RETAINED_NOTE =
            "Employment record retained on a legal basis; applicant-stage data was erased by the other services";
    private static final String EMPLOYEES = "employees";
    private static final String SAGAS = "hire_sagas";

    private final MongoTemplate mongo;

    PrivacyData(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public String service() {
        return "employee-service";
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var employees = new ArrayList<Map<String, Object>>();
        for (var row : rows(EMPLOYEES, tenantId, candidateId)) {
            var item = new LinkedHashMap<String, Object>();
            for (var field : List.of(
                    "employeeNo",
                    "name",
                    "email",
                    "phone",
                    "jobTitle",
                    "joiningDate",
                    "designation",
                    "location",
                    "managerName",
                    "department",
                    "status",
                    "activatedAt"))
                if (row.get(field) != null) item.put(field, PrivacyJson.safe(row.get(field), Set.of()));
            item.put(
                    "onboardingTasks",
                    documents(row.get("tasks")).stream()
                            .map(task -> PrivacyJson.map(task, "id"))
                            .toList());
            employees.add(item);
        }
        var sagas = new ArrayList<Map<String, Object>>();
        for (var row : rows(SAGAS, tenantId, candidateId)) {
            var item = new LinkedHashMap<String, Object>();
            for (var field : List.of("candidateName", "jobTitle", "joiningDate", "designation", "location", "status"))
                if (row.get(field) != null) item.put(field, PrivacyJson.safe(row.get(field), Set.of()));
            if (row.get("snapshot") != null)
                item.put("contactCopy", PrivacyJson.map(documentOf(row.get("snapshot")), "actorId", "source"));
            sagas.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("employees", employees);
        result.put("hireSagas", sagas);
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        int anonymised = 0;
        for (var row : rows(SAGAS, tenantId, candidateId)) {
            boolean employeeCreated = row.get("employeeId") != null;
            boolean holdsCopies = !ERASED_NAME.equals(row.getString("candidateName")) || row.get("snapshot") != null;
            if (!employeeCreated || !holdsCopies) continue;
            mongo.updateFirst(
                    Query.query(
                            Criteria.where("tenantId").is(tenantId).and("_id").is(row.get("_id"))),
                    new Update()
                            .set("candidateName", ERASED_NAME)
                            .unset("snapshot")
                            .inc("revision", 1),
                    SAGAS);
            anonymised++;
        }
        int employees = rows(EMPLOYEES, tenantId, candidateId).size();
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("hireSagasAnonymised", anonymised);
        if (employees > 0) {
            counts.put("employeeRecordsRetained", employees);
            return Outcome.retained(RETAINED_NOTE, counts);
        }
        return Outcome.erased(counts);
    }

    private List<Document> rows(String collection, String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("candidateId")
                        .is(candidateId)),
                Document.class,
                collection);
    }

    private static Document documentOf(Object value) {
        return value instanceof Document document ? document : new Document();
    }
}
