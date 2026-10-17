package com.openhire.resumeprocessor;

import static org.assertj.core.api.Assertions.*;

import com.mongodb.client.MongoClients;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
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

/** Isolated Mongo + real socket/HTTP fault fixtures. Production scanner remains enabled and unchanged. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class ResumeRecoveryAcceptanceTest {
    @TempDir
    Path directory;

    private com.mongodb.client.MongoClient client;
    private MongoTemplate mongo;
    private ResumePipeline pipeline;
    private ServerSocket scanner;
    private HttpServer extractor;
    private final AtomicReference<String> scan = new AtomicReference<>("OK");
    private final AtomicReference<String> extract = new AtomicReference<>("OK");

    @BeforeEach
    void start() throws Exception {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase3_test_" + UUID.randomUUID().toString().replace("-", ""));
        scanner = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread.startVirtualThread(() -> {
            while (!scanner.isClosed()) {
                try (var socket = scanner.accept()) {
                    var input = new DataInputStream(socket.getInputStream());
                    while (input.readByte() != 0) {}
                    int count;
                    while ((count = input.readInt()) != 0) input.readNBytes(count);
                    if (scan.get().equals("TIMEOUT")) Thread.sleep(32000);
                    else
                        socket.getOutputStream()
                                .write(("stream: " + (scan.get().equals("FOUND") ? "Test.Signature FOUND" : "OK")
                                                + "\0")
                                        .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    if (scanner.isClosed()) return;
                }
            }
        });
        extractor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        extractor.createContext("/tika", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean ocr = "ocr_only".equals(exchange.getRequestHeaders().getFirst("X-Tika-PDFOcrStrategy"));
            byte[] text = (extract.get().equals("OCR") && !ocr
                            ? ""
                            : "Fixture resume with Java Spring Boot and backend engineering experience")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(extract.get().equals("FAIL") ? 500 : 200, text.length == 0 ? -1 : text.length);
            if (text.length > 0) exchange.getResponseBody().write(text);
            exchange.close();
        });
        extractor.start();
        pipeline = new ResumePipeline(
                mongo,
                directory.toString(),
                "http://localhost:" + extractor.getAddress().getPort(),
                "localhost",
                scanner.getLocalPort());
    }

    @AfterEach
    void close() throws Exception {
        if (pipeline != null) pipeline.shutdown();
        if (scanner != null) scanner.close();
        if (extractor != null) extractor.stop(0);
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
        pipeline.work();
        for (int i = 0; i < 90; i++) {
            var row = pipeline.result("fixture", id);
            if (java.util.Set.of("DONE", "FAILED", "REJECTED").contains(row.getString("status"))) return row;
            Thread.sleep(500);
        }
        throw new AssertionError("Resume work did not finish");
    }

    @Test
    void scannerTimeoutFailsClosedAndCanRecover() throws Exception {
        scan.set("TIMEOUT");
        var id = upload();
        assertThat(finish(id).getString("status")).isEqualTo("FAILED");
        assertThatThrownBy(() -> pipeline.download("fixture", id)).isInstanceOf(RuntimeException.class);
        scan.set("OK");
        pipeline.retry("fixture", id);
        assertThat(finish(id).getString("status")).isEqualTo("DONE");
    }

    @Test
    void malwareIsQuarantinedAndNotRetryable() throws Exception {
        scan.set("FOUND");
        var id = upload();
        assertThat(finish(id).getString("status")).isEqualTo("REJECTED");
        assertThatThrownBy(() -> pipeline.download("fixture", id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> pipeline.retry("fixture", id)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void extractionFailureRetriesAndShortTextUsesOcr() throws Exception {
        extract.set("FAIL");
        var id = upload();
        assertThat(finish(id).getString("status")).isEqualTo("FAILED");
        extract.set("OCR");
        pipeline.retry("fixture", id);
        var recovered = finish(id);
        assertThat(recovered.getString("status")).isEqualTo("DONE");
        assertThat(((Document) recovered.get("steps")).getString("ocr")).isEqualTo("PASSED");
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
