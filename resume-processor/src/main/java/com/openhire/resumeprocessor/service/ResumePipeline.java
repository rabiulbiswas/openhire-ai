package com.openhire.resumeprocessor.service;

import static com.openhire.support.Records.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ResumePipeline {
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(8);
    private final java.util.concurrent.ExecutorService workers =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private final MongoTemplate mongo;
    private final Path root;
    private final String tika, clamHost;
    private final int clamPort;

    @Value("${openhire.resume.scan-enabled:true}")
    private boolean scanEnabled = true;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    @jakarta.annotation.PostConstruct
    void validateScannerMode() {
        if (!scanEnabled) {
            var profiles = Set.of(environment.getActiveProfiles());
            if (!profiles.contains("local-no-scan")
                    || profiles.stream().anyMatch(p -> !Set.of("local-no-scan", "local", "dev", "oidc")
                            .contains(p))) {
                throw new IllegalStateException(
                        "Disabling resume scanning requires the isolated local-no-scan profile");
            }
            org.slf4j.LoggerFactory.getLogger(ResumePipeline.class)
                    .warn("LOCAL DEVELOPMENT ONLY: resume malware scanning disabled; use trusted synthetic files only");
        }
    }

    public ResumePipeline(
            MongoTemplate mongo,
            @Value("${openhire.resume.directory}") String directory,
            @Value("${openhire.resume.tika-url}") String tika,
            @Value("${openhire.resume.clamav-host}") String clamHost,
            @Value("${openhire.resume.clamav-port}") int clamPort)
            throws IOException {
        this.mongo = mongo;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.tika = tika;
        this.clamHost = clamHost;
        this.clamPort = clamPort;
        Files.createDirectories(root);
    }

    public static String validate(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Resume must be 1 byte to 10 MB");
        if (bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
            return "application/pdf";
        byte[] ole = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), ole)) return "application/msword";
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            boolean document = false, contentTypes = false;
            long total = 0;
            int count = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (++count > 2000
                            || entry.getName().startsWith("/")
                            || entry.getName().contains("..")
                            || entry.getName().contains("\\"))
                        throw new IllegalArgumentException("Unsafe DOCX archive");
                    if (entry.getName().equals("word/document.xml")) document = true;
                    if (entry.getName().equals("[Content_Types].xml")) contentTypes = true;
                    if (entry.getName().toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"))
                        throw new IllegalArgumentException("Macro-enabled resumes are not accepted");
                    byte[] block = new byte[8192];
                    int read;
                    while ((read = zip.read(block)) != -1) {
                        total += read;
                        if (total > 50L * 1024 * 1024)
                            throw new IllegalArgumentException("DOCX expanded size exceeds 50 MB");
                    }
                }
            }
            if (document && contentTypes)
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        throw new IllegalArgumentException("Only PDF, DOC or DOCX file contents are supported");
    }

    private Query key(String tenant, String id) {
        return Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(id));
    }

    private com.openhire.support.privacy.Tombstones tombstones;

    /** Optional so the pipeline can be built without the privacy framework in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    public Document upload(String tenant, String applicationId, String name, byte[] bytes) throws Exception {
        if (tombstones != null && tombstones.isErased(tenant, applicationId))
            throw conflict("The data of this application was erased; a resume cannot be added");
        String mime = validate(bytes),
                digest =
                        HexFormat.of()
                                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(bytes));
        String id = hash(tenant, applicationId, digest);
        Path target = root.resolve(id);
        if (!Files.exists(target)) {
            Path temp = Files.createTempFile(root, "quarantine-", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException ignored) {
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        String safeName = name == null ? "resume" : name.replaceAll("[\\r\\n\\\\/]", "_");
        if (safeName.length() > 200) safeName = safeName.substring(0, 200);
        var row = new Document("_id", id)
                .append("tenantId", tenant)
                .append("applicationId", applicationId)
                .append("name", safeName)
                .append("mime", mime)
                .append("sha256", digest)
                .append("status", "PENDING")
                .append("attempts", 0)
                .append("uploadedAt", now())
                .append("steps", new Document("validation", "PASSED"));
        try {
            mongo.insert(row, "resumes");
        } catch (DuplicateKeyException ignored) {
        }
        return result(tenant, id);
    }

    public Document result(String tenant, String id) {
        var row = mongo.findOne(key(tenant, id), Document.class, "resumes");
        if (row == null) throw missing();
        row.put("id", row.remove("_id"));
        row.remove("_events");
        row.remove("lease");
        return row;
    }

    public void retry(String tenant, String id) {
        var q = key(tenant, id);
        q.addCriteria(Criteria.where("status").is("FAILED").and("attempts").lt(3));
        if (mongo.updateFirst(
                                q,
                                new Update()
                                        .set("status", "PENDING")
                                        .unset("error")
                                        .unset("nextAttempt"),
                                "resumes")
                        .getMatchedCount()
                == 0) throw conflict("Resume is not retryable or reached its attempt limit; upload a replacement");
    }

    public byte[] download(String tenant, String id) throws IOException {
        var row = result(tenant, id);
        if (!"DONE".equals(row.getString("status")))
            throw conflict("Resume is quarantined until scanning and extraction succeed");
        return Files.readAllBytes(root.resolve(id));
    }

    @Scheduled(fixedDelay = 250)
    public void work() {
        if (workers.isShutdown()) return;
        for (int i = 0; i < 8 && capacity.tryAcquire(); i++) {
            try {
                workers.submit(() -> {
                    try {
                        processOne();
                    } catch (RuntimeException failure) {
                        // Executor tasks swallow exceptions; log so a broken worker is never silent.
                        org.slf4j.LoggerFactory.getLogger(ResumePipeline.class)
                                .warn("Resume worker failed: {}", failure.toString());
                    } finally {
                        capacity.release();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                capacity.release();
                return;
            }
        }
    }

    private void processOne() {
        var exhausted = Query.query(Criteria.where("status")
                .is("PROCESSING")
                .and("leaseUntil")
                .lt(now())
                .and("attempts")
                .gte(3));
        mongo.updateMulti(
                exhausted,
                new Update()
                        .set("status", "FAILED")
                        .set("error", "ATTEMPTS_EXHAUSTED")
                        .unset("lease")
                        .unset("leaseUntil"),
                "resumes");
        String lease = UUID.randomUUID().toString();
        var q = Query.query(claimable(now()));
        var row = mongo.findAndModify(
                q,
                new Update()
                        .set("status", "PROCESSING")
                        .set("lease", lease)
                        .set("leaseUntil", Instant.now().plusSeconds(240).toString())
                        .inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true),
                Document.class,
                "resumes");
        if (row == null) return;
        String tenant = row.getString("tenantId"), id = row.getString("_id");
        var owned = key(tenant, id);
        owned.addCriteria(Criteria.where("lease").is(lease));
        var steps = doc(row.get("steps"));
        String status = "DONE", error = "", text = "";
        try {
            byte[] bytes = Files.readAllBytes(root.resolve(id));
            if (scanEnabled) {
                scan(bytes);
                steps.put("virusScan", "PASSED");
            } else {
                steps.put("virusScan", "SKIPPED_LOCAL_DEV");
            }
            text = extract(bytes, row.getString("mime"), "no_ocr");
            steps.put("extract", "PASSED");
            if (text.strip().length() < 30 && "application/pdf".equals(row.getString("mime"))) {
                text = extract(bytes, row.getString("mime"), "ocr_only");
                steps.put("ocr", "PASSED");
            }
            if (text.strip().length() < 30) throw new IOException("NO_EXTRACTABLE_TEXT");
        } catch (Infected infected) {
            status = "REJECTED";
            error = "MALWARE_DETECTED";
            steps.put("virusScan", "REJECTED");
        } catch (Exception exception) {
            status = "FAILED";
            error = exception.getMessage() == null ? "PROCESSING_FAILED" : exception.getMessage();
            if (error.length() > 200) error = "PROCESSING_FAILED";
            int attempt = ((Number) row.getOrDefault("attempts", 1)).intValue();
            if (transientFailure(exception) && attempt < 3) {
                // Fail closed (no unscanned resume is released) but let a brief scanner or extractor outage recover.
                mongo.updateFirst(
                        owned,
                        new Update()
                                .set("status", "PENDING")
                                .set("error", error)
                                .set(
                                        "nextAttempt",
                                        Instant.now().plusSeconds(15L * attempt).toString())
                                .unset("lease")
                                .unset("leaseUntil"),
                        "resumes");
                return;
            }
        }
        var sections = new Document();
        String[] lines = text.split("\\R");
        var current = new StringBuilder();
        String heading = "summary";
        for (String line : lines) {
            String normalized = line.trim().toLowerCase(Locale.ROOT);
            if (Set.of("skills", "experience", "education", "projects", "certifications")
                    .contains(normalized)) {
                sections.put(heading, current.toString());
                current = new StringBuilder();
                heading = normalized;
            } else current.append(line).append('\n');
        }
        sections.put(heading, current.toString());
        var event = event(
                status.equals("DONE") ? "ResumeProcessed" : "ResumeProcessingFailed",
                tenant,
                "Resume",
                id,
                Map.of("applicationId", row.getString("applicationId"), "status", status, "code", error));
        mongo.updateFirst(
                owned,
                new Update()
                        .set("status", status)
                        .set("error", error)
                        .set("text", status.equals("DONE") ? text : "")
                        .set("sections", status.equals("DONE") ? sections : new Document())
                        .set("steps", steps)
                        .set("updatedAt", now())
                        .unset("lease")
                        .unset("leaseUntil")
                        .push("_events", event),
                "resumes");
    }

    private void scan(byte[] bytes) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamHost, clamPort), 5000);
            socket.setSoTimeout(30000);
            var out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < bytes.length; offset += 8192) {
                int length = Math.min(8192, bytes.length - offset);
                out.writeInt(length);
                out.write(bytes, offset, length);
            }
            out.writeInt(0);
            out.flush();
            var response = new ByteArrayOutputStream();
            int next;
            while ((next = socket.getInputStream().read()) > 0) {
                if (response.size() > 4096) throw new IOException("INVALID_SCANNER_RESPONSE");
                response.write(next);
            }
            String result = response.toString(StandardCharsets.UTF_8);
            if (result.contains("FOUND")) throw new Infected();
            if (!result.trim().endsWith(": OK")) throw new Unavailable("SCANNER_UNAVAILABLE");
        }
    }

    /** A dependency (scanner or extractor) is temporarily unable to serve the request. */
    static final class Unavailable extends IOException {
        Unavailable(String message) {
            super(message);
        }
    }

    /** Work a worker may claim: pending or abandoned, under the attempt limit, and past any retry back-off. */
    static Criteria claimable(String now) {
        return new Criteria()
                .andOperator(
                        new Criteria()
                                .orOperator(
                                        Criteria.where("status").is("PENDING"),
                                        Criteria.where("status")
                                                .is("PROCESSING")
                                                .and("leaseUntil")
                                                .lt(now)),
                        Criteria.where("attempts").lt(3),
                        new Criteria()
                                .orOperator(
                                        Criteria.where("nextAttempt").exists(false),
                                        Criteria.where("nextAttempt").lte(now)));
    }

    static boolean transientFailure(Exception exception) {
        return exception instanceof Unavailable
                || exception instanceof java.net.ConnectException
                || exception instanceof java.net.SocketTimeoutException
                || exception instanceof java.net.UnknownHostException
                || exception instanceof java.net.NoRouteToHostException;
    }

    private String extract(byte[] bytes, String mime, String strategy) throws Exception {
        var connection = (HttpURLConnection) URI.create(tika + "/tika").toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(75000);
        connection.setRequestMethod("PUT");
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Content-Type", mime);
        connection.setRequestProperty("Accept", "text/plain");
        connection.setRequestProperty("X-Tika-PDFOcrStrategy", strategy);
        long deadline = System.nanoTime() + Duration.ofSeconds(80).toNanos();
        try {
            try (var output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int code = connection.getResponseCode();
            if (code >= 500) throw new Unavailable("EXTRACTION_UNAVAILABLE");
            if (code != 200) throw new IOException("EXTRACTION_FAILED");
            connection.setReadTimeout(5000);
            try (var input = connection.getInputStream();
                    var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (System.nanoTime() > deadline) throw new IOException("EXTRACTION_TIMEOUT");
                    if (output.size() + read > 1024 * 1024) throw new IOException("TEXT_LIMIT_EXCEEDED");
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static class Infected extends IOException {}
}
