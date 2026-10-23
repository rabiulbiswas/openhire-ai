package com.openhire.applicationservice.messaging.consumer;

import static com.openhire.support.Records.doc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class EmployeeCreatedConsumerTest {
    private final Map<String, Document> rows = new LinkedHashMap<>();
    private final List<Document> updates = new ArrayList<>();
    private final MongoTemplate mongo = mock(MongoTemplate.class);

    @BeforeEach
    void setUp() {
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("submissions")))
                .thenAnswer(call ->
                        rows.get(((Query) call.getArgument(0)).getQueryObject().get("_id")));
        when(mongo.find(any(Query.class), eq(Document.class), eq("submissions")))
                .thenAnswer(call -> {
                    var query = ((Query) call.getArgument(0)).getQueryObject();
                    var hired = ((Document) query.get("_id")).get("$ne");
                    return rows.values().stream()
                            .filter(row -> row.get("candidateId").equals(query.get("candidateId")))
                            .filter(row -> !row.get("_id").equals(hired))
                            .filter(row ->
                                    !List.of("HIRED", "REJECTED", "WITHDRAWN").contains(row.get("stage")))
                            .toList();
                });
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq("submissions")))
                .thenAnswer(call -> {
                    var id = ((Query) call.getArgument(0)).getQueryObject().get("_id");
                    var update = ((Update) call.getArgument(1)).getUpdateObject();
                    var set = doc(update.get("$set"));
                    rows.get(id).put("stage", set.get("stage"));
                    updates.add(update);
                    return UpdateResult.acknowledged(1, 1L, null);
                });
        row("app-1", "OFFER");
        row("app-2", "INTERVIEWING");
        row("app-3", "REJECTED");
        rows.put(
                "other",
                new Document("_id", "other").append("candidateId", "cand-9").append("stage", "APPLIED"));
    }

    private void row(String id, String stage) {
        rows.put(
                id,
                new Document("_id", id)
                        .append("candidateId", "cand-1")
                        .append("stage", stage)
                        .append("revision", 3L));
    }

    private static String created(String applicationId) {
        return new Document("eventType", "employee.created.v1")
                .append("tenantId", "t1")
                .append("payload", new Document("applicationId", applicationId).append("employeeId", "e1"))
                .toJson();
    }

    @Test
    void offerBecomesHiredAndOtherActiveApplicationsAreClosed() {
        new EmployeeCreatedConsumer(mongo, true).consume(created("app-1"));

        assertThat(rows.get("app-1").get("stage")).isEqualTo("HIRED");
        assertThat(rows.get("app-2").get("stage")).isEqualTo("REJECTED");
        assertThat(rows.get("app-3").get("stage")).isEqualTo("REJECTED");
        assertThat(rows.get("other").get("stage")).isEqualTo("APPLIED");
        assertThat(updates).hasSize(2);
        var closing = doc(doc(updates.get(1).get("$push")).get("decisions"));
        assertThat(closing.get("reason")).isEqualTo("HIRED_ELSEWHERE");
        var event = doc(doc(updates.get(0).get("$push")).get("_events"));
        assertThat(event.get("eventType")).isEqualTo("ApplicationStageChanged");
        assertThat(doc(event.get("payload")).get("to")).isEqualTo("HIRED");
    }

    @Test
    void redeliveryChangesNothingMore() {
        var consumer = new EmployeeCreatedConsumer(mongo, true);
        consumer.consume(created("app-1"));
        updates.clear();
        consumer.consume(created("app-1"));

        assertThat(updates).isEmpty();
    }

    @Test
    void otherApplicationsStayOpenWhenThePolicyIsOff() {
        new EmployeeCreatedConsumer(mongo, false).consume(created("app-1"));

        assertThat(rows.get("app-1").get("stage")).isEqualTo("HIRED");
        assertThat(rows.get("app-2").get("stage")).isEqualTo("INTERVIEWING");
    }

    @Test
    void applicationOutsideOfferIsLeftAlone() {
        new EmployeeCreatedConsumer(mongo, true).consume(created("app-2"));

        assertThat(rows.get("app-2").get("stage")).isEqualTo("INTERVIEWING");
        assertThat(updates).isEmpty();
    }

    @Test
    void unrelatedEventsAreIgnored() {
        new EmployeeCreatedConsumer(mongo, true)
                .consume(new Document("eventType", "employee.hris-sync.requested.v1")
                        .append("tenantId", "t1")
                        .toJson());

        assertThat(updates).isEmpty();
    }
}
