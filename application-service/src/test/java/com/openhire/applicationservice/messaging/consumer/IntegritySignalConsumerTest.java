package com.openhire.applicationservice.messaging.consumer;

import static com.openhire.support.Records.doc;
import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.applicationservice.service.FakeSubmissions;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegritySignalConsumerTest {
    private final FakeSubmissions submissions = new FakeSubmissions();
    private final IntegritySignalConsumer consumer = new IntegritySignalConsumer(submissions.mongo);

    @BeforeEach
    void setUp() {
        submissions.put(new Document("_id", "app-1")
                .append("tenantId", "t1")
                .append("stage", "AI_ASSESSED")
                .append("revision", 5L)
                .append("assessment", new Document("status", "COMPLETE").append("score", 80))
                .append("score", 80));
        submissions.put(new Document("_id", "app-2")
                .append("tenantId", "t1")
                .append("stage", "APPLIED")
                .append("revision", 1L));
    }

    private static String raised(String application, String signalId, String type) {
        return new Document("eventType", "integrity.signal.raised.v1")
                .append("tenantId", "t1")
                .append(
                        "payload",
                        new Document("applicationId", application)
                                .append("signalId", signalId)
                                .append("type", type)
                                .append("severity", "HIGH")
                                .append("confidence", 0.9)
                                .append("evidence", "Two full-time roles overlap for 11 months."))
                .toJson();
    }

    private static String dismissed(String application, String signalId) {
        return new Document("eventType", "integrity.signal.dismissed.v1")
                .append("tenantId", "t1")
                .append("payload", new Document("applicationId", application).append("signalId", signalId))
                .toJson();
    }

    @Test
    void raisedSignalBecomesAFlagOnTheAssessmentAndTheFlatReasonForTheUi() {
        consumer.consume(raised("app-1", "sig-1", "TIMELINE_OVERLAP"));

        var row = submissions.rows.get("app-1");
        var flags = doc(row.get("assessment")).get("integrityFlags");
        assertThat(flags).isInstanceOf(List.class);
        var flag = doc(((List<?>) flags).get(0));
        assertThat(flag.getString("id")).isEqualTo("sig-1");
        assertThat(flag.getString("type")).isEqualTo("TIMELINE_OVERLAP");
        assertThat(flag.getString("severity")).isEqualTo("HIGH");
        assertThat(flag.getString("reason")).isEqualTo("Two full-time roles overlap for 11 months.");
        assertThat(doc(doc(row.get("assessment")).get("integrityFlag")).getString("reason"))
                .isEqualTo("Two full-time roles overlap for 11 months.");
        assertThat(row.get("integrityFlag")).isEqualTo(true);
    }

    @Test
    void signalsNeverChangeTheScoreStageOrRevision() {
        consumer.consume(raised("app-1", "sig-1", "TIMELINE_OVERLAP"));
        consumer.consume(raised("app-1", "sig-2", "INJECTION_ATTEMPT"));

        var row = submissions.rows.get("app-1");
        assertThat(row.get("stage")).isEqualTo("AI_ASSESSED");
        assertThat(row.get("score")).isEqualTo(80);
        assertThat(doc(row.get("assessment")).get("score")).isEqualTo(80);
        assertThat(row.get("revision")).isEqualTo(5L);
        assertThat((List<?>) row.get("integrityFlags")).hasSize(2);
    }

    @Test
    void redeliveryDoesNotDuplicateTheFlag() {
        consumer.consume(raised("app-1", "sig-1", "TIMELINE_OVERLAP"));
        consumer.consume(raised("app-1", "sig-1", "TIMELINE_OVERLAP"));
        assertThat((List<?>) submissions.rows.get("app-1").get("integrityFlags"))
                .hasSize(1);
        assertThat(submissions.updates).hasSize(1);
    }

    @Test
    void aSignalBeforeTheAssessmentExistsDoesNotCreateAHalfAssessment() {
        consumer.consume(raised("app-2", "sig-9", "DUPLICATE_IDENTITY"));

        var row = submissions.rows.get("app-2");
        assertThat(row.containsKey("assessment")).isFalse();
        assertThat((List<?>) row.get("integrityFlags")).hasSize(1);
    }

    @Test
    void dismissedSignalsAreRemovedAndTheFlatFlagClearsWhenNoneRemain() {
        consumer.consume(raised("app-1", "sig-1", "TIMELINE_OVERLAP"));
        consumer.consume(raised("app-1", "sig-2", "INJECTION_ATTEMPT"));

        consumer.consume(dismissed("app-1", "sig-1"));
        var row = submissions.rows.get("app-1");
        assertThat((List<?>) row.get("integrityFlags")).hasSize(1);
        assertThat(row.get("integrityFlag")).isEqualTo(true);

        consumer.consume(dismissed("app-1", "sig-2"));
        assertThat((List<?>) row.get("integrityFlags")).isEmpty();
        assertThat(row.containsKey("integrityFlag")).isFalse();
        assertThat(doc(row.get("assessment")).containsKey("integrityFlag")).isFalse();
    }
}
