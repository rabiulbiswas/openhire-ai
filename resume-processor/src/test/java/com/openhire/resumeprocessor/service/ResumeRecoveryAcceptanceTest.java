package com.openhire.resumeprocessor.service;

import static org.assertj.core.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;

/** Isolated Mongo and injected extraction faults. Real parsing has separate fixtures. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class ResumeRecoveryAcceptanceTest {
    @TempDir
    Path directory;

    private com.mongodb.client.MongoClient client;
    private MongoTemplate mongo;
    private ResumePipeline pipeline;
    private final AtomicReference<String> extract = new AtomicReference<>("OK");

    @BeforeEach
    void start() throws Exception {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase3_test_" + UUID.randomUUID().toString().replace("-", ""));
        pipeline = new ResumePipeline(
                mongo,
                directory.toString(),
                (bytes, mime, ocr) -> {
                    if (extract.get().equals("FAIL")) throw new ResumePipeline.Unavailable("EXTRACTION_UNAVAILABLE");
                    return extract.get().equals("OCR") && !ocr
                            ? ""
                            : "Fixture resume with Java Spring Boot and backend engineering experience";
                },
                1);
    }

    @AfterEach
    void close() throws Exception {
        if (pipeline != null) pipeline.shutdown();
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    private String upload() throws Exception {
        return pipeline.upload(
                        "fixture",
                        UUID.randomUUID().toString(),
                        "fixture.pdf",
                        "%PDF-1.4 fixture".getBytes(StandardCharsets.US_ASCII))
                .getString("id");
    }

    private Document finish(String id) throws Exception {
        return await(id, false);
    }

    private Document await(String id, boolean retryPending) throws Exception {
        Document row = null;
        for (int i = 0; i < 90; i++) {
            // This fixture constructs the service directly, so there is no Spring scheduler.
            pipeline.work();
            row = pipeline.result("fixture", id);
            if (retryPending) {
                if ("PENDING".equals(row.getString("status"))
                        && row.containsKey("nextAttempt")
                        && Instant.parse(row.getString("nextAttempt")).isAfter(Instant.now())) return row;
            } else if (java.util.Set.of("DONE", "FAILED", "REJECTED").contains(row.getString("status"))) {
                return row;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Resume did not reach " + (retryPending ? "retry backoff" : "a terminal state")
                + "; last state=" + row);
    }

    private void assertQuarantinedBackoff(String id) throws Exception {
        var pending = await(id, true);
        assertThat(pending.getInteger("attempts")).isEqualTo(1);
        assertThat(Instant.parse(pending.getString("nextAttempt"))).isAfter(Instant.now());
        assertThatThrownBy(() -> pipeline.download("fixture", id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> pipeline.retry("fixture", id)).isInstanceOf(RuntimeException.class);
        var stored = mongo.findOne(Query.query(Criteria.where("_id").is(id)), Document.class, "resumes");
        assertThat(stored).doesNotContainKeys("lease", "leaseUntil");
        assertThat(mongo.count(
                        Query.query(ResumePipeline.claimable(Instant.now().toString())), "resumes"))
                .isZero();
    }

    private void makeRetryDue(String id) {
        // Advance only this isolated fixture's backoff; production retry delays remain unchanged.
        mongo.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().set("nextAttempt", Instant.EPOCH.toString()),
                "resumes");
    }

    @Test
    void processesWithoutScannerAndDoesNotClaimAScanPassed() throws Exception {
        var id = upload();
        mongo.updateFirst(
                Query.query(Criteria.where("_id").is(id)), new Update().set("steps.virusScan", "PASSED"), "resumes");
        var result = finish(id);
        assertThat(result.getString("status")).isEqualTo("DONE");
        assertThat(((Document) result.get("steps")).getString("virusScan")).isEqualTo("NOT_PERFORMED");
        assertThat(((Document) result.get("steps")).getString("extract")).isEqualTo("PASSED");
        assertThat(result.getInteger("attempts")).isEqualTo(1);
        assertThat(pipeline.download("fixture", id)).isNotEmpty();
    }

    @Test
    void extractionFailureRetriesAndShortTextUsesOcr() throws Exception {
        extract.set("FAIL");
        var id = upload();
        assertQuarantinedBackoff(id);
        extract.set("OCR");
        makeRetryDue(id);
        var recovered = finish(id);
        assertThat(recovered.getString("status")).isEqualTo("DONE");
        assertThat(recovered.getInteger("attempts")).isEqualTo(2);
        assertThat(((Document) recovered.get("steps")).getString("ocr")).isEqualTo("PASSED");
    }

    @Test
    void repeatedExtractorOutageStopsAtAttemptLimit() throws Exception {
        extract.set("FAIL");
        var id = upload();
        assertQuarantinedBackoff(id);
        makeRetryDue(id);
        assertThat(await(id, true).getInteger("attempts")).isEqualTo(2);
        makeRetryDue(id);
        var failed = finish(id);
        assertThat(failed.getString("status")).isEqualTo("FAILED");
        assertThat(failed.getInteger("attempts")).isEqualTo(3);
        assertThat(failed.getString("error")).isEqualTo("EXTRACTION_UNAVAILABLE");
        assertThatThrownBy(() -> pipeline.download("fixture", id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> pipeline.retry("fixture", id)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void abandonedLeaseIsRecoveredAndExhaustedLeaseStops() throws Exception {
        var id = upload();
        mongo.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("status", "PROCESSING")
                        .set("leaseUntil", Instant.EPOCH.toString())
                        .set("attempts", 1),
                "resumes");
        assertThat(finish(id).getString("status")).isEqualTo("DONE");
        var exhausted = upload();
        mongo.updateFirst(
                Query.query(Criteria.where("_id").is(exhausted)),
                new Update()
                        .set("status", "PROCESSING")
                        .set("leaseUntil", Instant.EPOCH.toString())
                        .set("attempts", 3),
                "resumes");
        assertThat(finish(exhausted).getString("error")).isEqualTo("ATTEMPTS_EXHAUSTED");
    }
}
