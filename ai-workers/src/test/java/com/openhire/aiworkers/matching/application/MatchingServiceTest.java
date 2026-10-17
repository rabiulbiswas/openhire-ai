package com.openhire.aiworkers.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.aiworkers.matching.application.MatchingMemory.Assessments;
import com.openhire.aiworkers.matching.application.MatchingMemory.Chunks;
import com.openhire.aiworkers.matching.application.MatchingMemory.FakeGateway;
import com.openhire.aiworkers.matching.application.MatchingMemory.MutableClock;
import com.openhire.aiworkers.matching.application.MatchingMemory.Retries;
import com.openhire.aiworkers.matching.application.MatchingMemory.Vectors;
import com.openhire.aiworkers.matching.domain.Lexical;
import com.openhire.aiworkers.matching.domain.MatchingRequest;
import com.openhire.aiworkers.matching.domain.Taxonomy;
import com.openhire.aiworkers.shared.application.port.ResumeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingServiceTest {
    private static final String RESUME =
            """
            Backend engineer.

            EXPERIENCE
            Built Kafka pipelines in Java and Spring Boot at Acme. Led a team of four.

            SKILLS
            Java, Kafka, Spring Boot, SQL

            EDUCATION
            BTech
            """;

    private final FakeGateway gateway = new FakeGateway();
    private final Chunks chunks = new Chunks();
    private final Vectors vectors = new Vectors();
    private final Assessments assessments = new Assessments();
    private final Retries retries = new Retries();
    private final MutableClock clock = new MutableClock();
    private final AtomicReference<String> resumeText = new AtomicReference<>(RESUME);
    private MatchingService service;

    @BeforeEach
    void setUp() {
        service = service(1);
    }

    private MatchingService service(int concurrency) {
        ResumeSource resumes = (tenant, id) -> new ResumeSource.Resume("DONE", resumeText.get(), "resume.pdf");
        return new MatchingService(
                gateway,
                resumes,
                chunks,
                vectors,
                assessments,
                retries,
                new Lexical(Taxonomy.builtIn()),
                new MatchingSettings(
                        concurrency,
                        List.of(Duration.ofSeconds(60), Duration.ofSeconds(300), Duration.ofSeconds(1800)),
                        Duration.ofDays(3),
                        Duration.ofMinutes(10)),
                clock);
    }

    static MatchingRequest request(String eventId, String applicationId, Map<String, Object> overrides) {
        var payload = MatchingRequestFixtures.payload(applicationId);
        payload.putAll(overrides);
        return MatchingRequest.fromEnvelope(
                Map.of("eventId", eventId, "eventType", "matching.requested.v1", "tenantId", "t1", "payload", payload));
    }

    private static MatchingRequest request(String eventId) {
        return request(eventId, "app-1", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> criteria(MatchingMemory.Saved saved) {
        return (List<Map<String, Object>>) saved.payload().get("criteria");
    }

    @Test
    void completeAssessmentFollowsTheContractAndCalibration() {
        service.handle(request("evt-1"));

        var saved = assessments.versions();
        assertThat(saved).hasSize(1);
        var event = saved.get(0).event;
        assertThat(event.get("eventType")).isEqualTo("assessment.completed.v1");
        assertThat(event.get("aggregateType")).isEqualTo("Assessment");
        assertThat(event.get("aggregateId")).isEqualTo("app-1");
        var payload = saved.get(0).payload();
        assertThat(payload.get("status")).isEqualTo("COMPLETE");
        assertThat(payload.get("label")).isEqualTo("AI recommendation - requires human review");
        assertThat(payload.get("jobVersion")).isEqualTo(2);
        assertThat(payload.get("rubricVersion")).isEqualTo("2");
        // Java and Kafka: 0.7 * 4/5 + 0.3 = 0.86 (weights 2/5 each); Payments: ungrounded, no coverage = 0.
        assertThat(payload.get("score")).isEqualTo(69);
        assertThat(payload.get("confidence")).isEqualTo("MEDIUM");
        assertThat(payload.get("hardGaps")).isEqualTo(List.of());
        assertThat(payload.get("gapsToProbe")).isEqualTo(List.of("Payments domain"));
        assertThat((Map<String, Object>) payload.get("provenance"))
                .containsEntry("model", "qwen2.5:3b-instruct")
                .containsEntry("promptKey", "match.criterion.judge")
                .containsEntry("promptVersion", "1")
                .containsEntry("rubricVersion", "2")
                .containsKey("createdAt")
                .containsEntry("traceId", "trace-1");
        var java = criteria(saved.get(0)).get(0);
        assertThat(java)
                .containsEntry("id", "r-java")
                .containsEntry("requirement", "MUST")
                .containsEntry("grounded", true);
        assertThat(java.get("evidence")).isEqualTo(List.of("Java"));
        assertThat(criteria(saved.get(0)).get(2))
                .containsEntry("grounded", false)
                .containsEntry("score", 0);
    }

    @Test
    void retrievalSendsOnlyTheCandidatesOwnChunksAndAtMostFive() {
        service.handle(request("evt-1"));

        assertThat(gateway.shown).hasSize(3).allSatisfy(shown -> assertThat(shown)
                .hasSizeLessThanOrEqualTo(5));
        assertThat(chunks.chunkIds("t1", "res-1")).isNotEmpty();
        assertThat(chunks.chunks("t1", "someone-else", "res-1")).isEmpty();
    }

    @Test
    void aHallucinatedQuoteMakesTheCriterionInsufficientEvidence() {
        gateway.hallucinate.add("r-kafka");
        service.handle(request("evt-1"));

        var saved = assessments.versions().get(0);
        var kafka = criteria(saved).get(1);
        assertThat(kafka).containsEntry("grounded", false).containsEntry("evidence", List.of());
        // 0.5 * lexical(1.0) instead of the judge's 5/5
        assertThat(kafka.get("score")).isEqualTo(50);
        // mixed: one criterion's quotes were discarded, so the assessment is flagged ungrounded
        assertThat(saved.payload().get("status")).isEqualTo("PARTIAL");
        assertThat(criteria(saved).get(0)).containsEntry("grounded", true);
    }

    @Test
    void nothingRelevantInTheResumeIsACompleteLowScoringAssessment() {
        resumeText.set("Office administrator.\n\nEXPERIENCE\nManaged the front desk and filing.");
        service.handle(request("evt-1"));

        var payload = assessments.versions().get(0).payload();
        assertThat(payload.get("status")).isEqualTo("COMPLETE");
        assertThat(payload.get("label")).isEqualTo("AI recommendation - requires human review");
        assertThat(payload.get("score")).isEqualTo(0);
        assertThat(payload.get("confidence")).isEqualTo("LOW");
        assertThat(payload.get("hardGaps")).isEqualTo(List.of("r-java", "r-kafka"));
        assertThat(retries.rows).isEmpty();
    }

    @Test
    void hallucinatedQuotesEverywhereArePartialWithoutRetry() {
        gateway.hallucinate.addAll(List.of("r-java", "r-kafka", "r-payments"));
        service.handle(request("evt-1"));

        var saved = assessments.versions().get(0);
        assertThat(saved.payload().get("status")).isEqualTo("PARTIAL");
        assertThat(saved.payload().get("label")).isEqualTo("AI unavailable, partial assessment");
        assertThat(retries.rows).isEmpty();
    }

    @Test
    void failedKnockoutIsNotEligibleAndNeverCallsTheGateway() {
        service.handle(request("evt-1", "app-1", Map.of("answers", Map.of("permit", "no"))));

        var payload = assessments.versions().get(0).payload();
        assertThat(payload.get("status")).isEqualTo("NOT_ELIGIBLE");
        assertThat(payload.get("failedRules")).isEqualTo(List.of("Work permit?"));
        assertThat(gateway.embedCalls.get()).isZero();
    }

    @Test
    void gatewayDownRecordsAPartialAssessmentThenARetrySupersedesItWithAComplete() {
        gateway.down = true;
        service.handle(request("evt-1"));

        var partial = assessments.versions().get(0);
        assertThat(partial.payload().get("status")).isEqualTo("PARTIAL");
        assertThat(partial.payload().get("label")).isEqualTo("AI unavailable, partial assessment");
        assertThat(partial.payload().get("confidence")).isEqualTo("LOW");
        // lexical coverage only: Java and Kafka present, Payments not (weights 2, 2, 1)
        assertThat(partial.payload().get("score")).isEqualTo(80);
        assertThat(((Map<?, ?>) partial.payload().get("provenance")).get("model"))
                .isEqualTo("lexical-taxonomy-v1");
        assertThat(retries.rows).hasSize(1);

        assertThat(service.runDueRetries(5)).isZero();

        clock.advance(Duration.ofSeconds(61));
        assertThat(service.runDueRetries(5)).isEqualTo(1);
        assertThat(assessments.versions()).hasSize(1);
        assertThat(retries.rows.values().iterator().next().next())
                .isEqualTo(clock.instant().plusSeconds(300));

        gateway.down = false;
        clock.advance(Duration.ofSeconds(301));
        service.runDueRetries(5);

        var versions = assessments.versions();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1).payload().get("status")).isEqualTo("COMPLETE");
        assertThat(versions.get(0).current).isFalse();
        assertThat(versions.get(1).current).isTrue();
        assertThat(retries.rows.values().iterator().next().state()).isEqualTo("DONE");
        assertThat(service.runDueRetries(5)).isZero();
    }

    @Test
    void retriesBackOffThenGoDailyAndStopAfterThreeDays() {
        gateway.down = true;
        service.handle(request("evt-1"));
        for (long seconds : List.of(300L, 1800L, 86400L, 86400L, 86400L)) {
            clock.advance(Duration.between(
                    clock.instant(), retries.rows.values().iterator().next().next()));
            service.runDueRetries(5);
            var row = retries.rows.values().iterator().next();
            assertThat(row.state()).isEqualTo("PENDING");
            assertThat(Duration.between(clock.instant(), row.next()).getSeconds())
                    .isEqualTo(seconds);
        }
        clock.advance(Duration.between(
                clock.instant(), retries.rows.values().iterator().next().next()));
        service.runDueRetries(5);

        assertThat(retries.rows.values().iterator().next().state()).isEqualTo("EXHAUSTED");
        assertThat(service.runDueRetries(5)).isZero();
    }

    @Test
    void aRetryReusesStoredChunksAndCachedCriterionVectors() {
        gateway.failJudge = true;
        service.handle(request("evt-1"));
        int embedsAfterFirstRun = gateway.embedCalls.get();
        assertThat(embedsAfterFirstRun).isEqualTo(2);

        gateway.failJudge = false;
        clock.advance(Duration.ofSeconds(61));
        service.runDueRetries(5);

        assertThat(gateway.embedCalls.get()).isEqualTo(embedsAfterFirstRun);
        assertThat(assessments.versions().get(1).payload().get("status")).isEqualTo("COMPLETE");
    }

    @Test
    void redeliveryAndRepeatedWorkAreIdempotent() {
        service.handle(request("evt-1"));
        int judged = gateway.judgeCalls.get();

        service.handle(request("evt-1"));
        service.handle(request("evt-2"));

        assertThat(assessments.versions()).hasSize(1);
        assertThat(gateway.judgeCalls.get()).isEqualTo(judged);
    }

    @Test
    void aNewJobVersionOrRubricIsNewWork() {
        service.handle(request("evt-1"));
        service.handle(request("evt-2", "app-1", Map.of("jobVersion", 3)));

        assertThat(assessments.versions()).hasSize(2);
        assertThat(assessments.versions().get(1).payload().get("jobVersion")).isEqualTo(3);
    }

    @Test
    void embedsInBatchesOfAtMostThirtyTwo() {
        resumeText.set("EXPERIENCE\n" + "java ".repeat(18000));
        service.handle(request("evt-1"));

        assertThat(chunks.chunkIds("t1", "res-1").size()).isGreaterThan(32);
        assertThat(gateway.maxBatch.get()).isLessThanOrEqualTo(32);
    }

    @Test
    void chunksAreReplacedForTheSameResumeNotAppended() {
        service.handle(request("evt-1"));
        var first = chunks.chunkIds("t1", "res-1");

        resumeText.set(RESUME + "\nPROJECTS\nA payments project.");
        service.handle(request("evt-2", "app-1", Map.of("jobVersion", 3)));

        assertThat(chunks.chunkIds("t1", "res-1")).hasSize(first.size() + 1);
    }

    @Test
    void concurrencyIsBoundedBySetting() throws Exception {
        gateway.judgeDelayMillis = 40;
        var one = service(1);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 3; i++) {
            var id = "app-" + i;
            threads.add(Thread.ofPlatform().start(() -> one.handle(request("evt-" + id, id, Map.of()))));
        }
        for (var thread : threads) thread.join();

        assertThat(assessments.versions()).hasSize(3);
        assertThat(gateway.maxRunning.get()).isEqualTo(1);
    }

    @Test
    void unprocessedResumeFailsSoTheCommandIsRedelivered() {
        ResumeSource pending = (tenant, id) -> new ResumeSource.Resume("PROCESSING", "", "");
        var waiting = new MatchingService(
                gateway,
                pending,
                chunks,
                vectors,
                assessments,
                retries,
                new Lexical(Taxonomy.builtIn()),
                new MatchingSettings(1, List.of(Duration.ofSeconds(60)), Duration.ofDays(3), Duration.ofMinutes(10)),
                clock);

        assertThatThrownBy(() -> waiting.handle(request("evt-1"))).isInstanceOf(IllegalStateException.class);
        assertThat(assessments.versions()).isEmpty();
    }
}
