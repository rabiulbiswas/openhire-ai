package com.openhire.applicationservice.workflow;

import static com.openhire.support.Records.*;

import com.openhire.support.Access;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Durable per-row import. A replay resumes staging; workers start only after the manifest is ready. */
@RestController
@RequestMapping("/api/v1/imports")
public class BulkImportController {
    private final SubmissionService service;
    private final Access access;

    public BulkImportController(SubmissionService service, Access access) {
        this.service = service;
        this.access = access;
    }

    private Query key(String tenant, String id) {
        return Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(id));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object start(
            Authentication auth,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam String jobId,
            @RequestParam boolean processingConsent,
            @RequestParam String consentVersion,
            @RequestPart MultipartFile file)
            throws Exception {
        access.requireStaff(auth);
        if (!processingConsent || !service.consentVersion(access.tenant(auth)).equals(consentVersion))
            throw new IllegalArgumentException("Current processing consent is required");
        if (key.isBlank() || key.length() > 200) throw new IllegalArgumentException("Invalid idempotency key");
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
            throw new IllegalArgumentException("Import must be 1 byte to 10 MB");
        String tenant = access.tenant(auth), actor = access.actor(auth), id = hash(tenant, actor, key);
        byte[] bytes = file.getBytes();
        String fingerprint = hash(
                jobId,
                consentVersion,
                HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(bytes)));
        var existing = service.store().findOne(key(tenant, id), Document.class, "imports");
        if (existing != null && !fingerprint.equals(text(existing, "fingerprint")))
            throw conflict("Import key already used for another file");
        if (existing != null && "READY".equals(text(existing, "state"))) return report(tenant, id);
        if (!"OPEN".equals(text(service.job(tenant, jobId), "status"))) throw conflict("Job is not open");
        var files = new HashMap<String, byte[]>();
        String csv;
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            long total = 0;
            int entries = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    String name = entry.getName();
                    if (++entries > 1001
                            || name.startsWith("/")
                            || name.contains("..")
                            || name.contains("\\")
                            || name.contains(":")) throw new IllegalArgumentException("Unsafe import archive");
                    if (entry.isDirectory()) continue;
                    byte[] content = zip.readNBytes(10 * 1024 * 1024 + 1);
                    total += content.length;
                    if (content.length > 10 * 1024 * 1024 || total > 50L * 1024 * 1024)
                        throw new IllegalArgumentException("Expanded archive too large");
                    if (files.putIfAbsent(name, content) != null)
                        throw new IllegalArgumentException("Duplicate archive entry");
                }
            }
            byte[] manifest = files.remove("candidates.csv");
            if (manifest == null)
                throw new IllegalArgumentException(
                        "ZIP must include candidates.csv and files named by its resume column");
            csv = new String(manifest, StandardCharsets.UTF_8);
        } else csv = new String(bytes, StandardCharsets.UTF_8);
        var rows = parseCsv(csv);
        if (rows.size() < 2 || rows.size() > 1001)
            throw new IllegalArgumentException("Import requires 1..1000 candidate rows");
        var header = rows.getFirst().stream().map(String::trim).toList();
        if (!header.containsAll(List.of("name", "email", "processingConsent"))
                || new HashSet<>(header).size() != header.size())
            throw new IllegalArgumentException(
                    "CSV requires unique name,email,processingConsent columns; optional phone,resume");
        var manifest = new Document("_id", id)
                .append("tenantId", tenant)
                .append("actor", actor)
                .append("fingerprint", fingerprint)
                .append("fileName", file.getOriginalFilename())
                .append("state", "STAGING")
                .append("total", rows.size() - 1)
                .append("createdAt", now());
        try {
            service.store().insert(manifest, "imports");
        } catch (DuplicateKeyException ignored) {
        }
        for (int index = 1; index < rows.size(); index++) {
            var values = rows.get(index);
            var input =
                    new Document("jobId", jobId).append("source", "RECRUITER").append("consentVersion", consentVersion);
            String error = "";
            if (values.size() != header.size()) error = "Column count does not match header";
            else
                for (int col = 0; col < header.size(); col++)
                    if (Set.of("name", "email", "processingConsent", "phone", "resume")
                            .contains(header.get(col))) input.put(header.get(col), values.get(col));
            boolean consent = Boolean.parseBoolean(text(input, "processingConsent"));
            String resume = text(input, "resume");
            if (!resume.isBlank() && !files.containsKey(resume)) error = "Referenced resume is missing from ZIP";
            String state = !error.isBlank() ? "FAILED" : !consent ? "SKIPPED" : "PENDING";
            var row = new Document("_id", hash(id, Integer.toString(index)))
                    .append("tenantId", tenant)
                    .append("importId", id)
                    .append("actor", actor)
                    .append("row", index + 1)
                    .append("input", input)
                    .append("state", state)
                    .append("reason", error)
                    .append("attempts", 0);
            if (files.containsKey(resume)) row.put("file", new Binary(files.get(resume)));
            try {
                service.store().insert(row, "import_rows");
            } catch (DuplicateKeyException ignored) {
            }
        }
        service.store().updateFirst(key(tenant, id), new Update().set("state", "READY"), "imports");
        return report(tenant, id);
    }

    @GetMapping("/{id}")
    public Object get(Authentication auth, @PathVariable String id) {
        access.requireStaff(auth);
        return report(access.tenant(auth), id);
    }

    private Document report(String tenant, String id) {
        var manifest = service.store().findOne(key(tenant, id), Document.class, "imports");
        if (manifest == null) throw missing();
        var query = Query.query(
                Criteria.where("tenantId").is(tenant).and("importId").is(id));
        query.fields().exclude("file").exclude("input");
        var rows = service.store().find(query, Document.class, "import_rows");
        long done = rows.stream().filter(r -> "DONE".equals(text(r, "state"))).count(),
                failed =
                        rows.stream()
                                .filter(r -> "FAILED".equals(text(r, "state")))
                                .count(),
                skipped =
                        rows.stream()
                                .filter(r -> "SKIPPED".equals(text(r, "state")))
                                .count();
        int total = ((Number) manifest.get("total")).intValue();
        boolean complete = "READY".equals(text(manifest, "state")) && done + failed + skipped == total;
        return new Document("id", id)
                .append("fileName", manifest.get("fileName"))
                .append("status", complete ? "DONE" : "PROCESSING")
                .append(
                        "totals",
                        Map.of(
                                "total",
                                total,
                                "imported",
                                done,
                                "mergedExisting",
                                0,
                                "skippedNoConsent",
                                skipped,
                                "failed",
                                failed))
                .append(
                        "failedRows",
                        rows.stream()
                                .filter(r -> "FAILED".equals(text(r, "state")))
                                .map(r -> Map.of("row", r.get("row"), "reason", text(r, "reason")))
                                .toList());
    }

    @Scheduled(fixedDelay = 1500)
    public void process() {
        for (var manifest : service.store()
                .find(
                        Query.query(Criteria.where("state")
                                        .is("READY")
                                        .and("finished")
                                        .ne(true))
                                .limit(20),
                        Document.class,
                        "imports")) {
            String tenant = manifest.getString("tenantId"), importId = manifest.getString("_id");
            var eligible = new Criteria()
                    .orOperator(
                            Criteria.where("state").is("PENDING"),
                            Criteria.where("state")
                                    .is("PROCESSING")
                                    .and("leaseUntil")
                                    .lt(now()));
            var query = Query.query(Criteria.where("tenantId")
                            .is(tenant)
                            .and("importId")
                            .is(importId))
                    .addCriteria(eligible);
            String lease = UUID.randomUUID().toString();
            var row = service.store()
                    .findAndModify(
                            query,
                            new Update()
                                    .set("state", "PROCESSING")
                                    .set("lease", lease)
                                    .set(
                                            "leaseUntil",
                                            Instant.now().plusSeconds(120).toString())
                                    .inc("attempts", 1),
                            FindAndModifyOptions.options().returnNew(true),
                            Document.class,
                            "import_rows");
            if (row == null) {
                if (!service.store()
                        .exists(
                                Query.query(Criteria.where("tenantId")
                                        .is(tenant)
                                        .and("importId")
                                        .is(importId)
                                        .and("state")
                                        .in("PENDING", "PROCESSING")),
                                "import_rows"))
                    if (service.store()
                                    .updateFirst(
                                            key(tenant, importId)
                                                    .addCriteria(Criteria.where("finished")
                                                            .ne(true)),
                                            new Update().set("finished", true),
                                            "imports")
                                    .getModifiedCount()
                            > 0) {
                        var totals = doc(report(tenant, importId).get("totals"));
                        service.importCompleted(tenant, importId, text(manifest, "actor"), totals);
                    }
                continue;
            }
            var owned = key(tenant, row.getString("_id"))
                    .addCriteria(Criteria.where("lease").is(lease));
            try {
                var input = doc(row.get("input"));
                Object stored = row.get("file");
                byte[] content = stored instanceof Binary binary
                        ? binary.getData()
                        : stored instanceof byte[] array ? array : null;
                MultipartFile resume = content == null ? null : new MemoryFile(text(input, "resume"), content);
                var application = service.submit(tenant, text(row, "actor"), row.getString("_id"), input, resume);
                service.store()
                        .updateFirst(
                                owned,
                                new Update()
                                        .set("state", "DONE")
                                        .set("applicationId", application.getString("_id"))
                                        .unset("file")
                                        .unset("lease"),
                                "import_rows");
            } catch (Exception exception) {
                boolean terminal =
                        exception instanceof IllegalArgumentException || ((Number) row.get("attempts")).intValue() >= 3;
                var update = new Update()
                        .set("state", terminal ? "FAILED" : "PENDING")
                        .set(
                                "reason",
                                exception instanceof IllegalArgumentException
                                        ? exception.getMessage()
                                        : "Submission failed; review the row and retry in a new import")
                        .unset("lease");
                if (terminal) update.unset("file");
                service.store().updateFirst(owned, update, "import_rows");
            }
        }
    }

    public static List<List<String>> parseCsv(String csv) {
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else cell.append(c);
            } else if (c == '"') {
                if (cell.length() != 0 || closed) throw new IllegalArgumentException("Invalid CSV quote");
                quoted = true;
            } else if (c == ',' || c == '\r' || c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                closed = false;
                if (c != ',') {
                    rows.add(row);
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                }
            } else {
                if (closed) throw new IllegalArgumentException("Unexpected text after CSV quote");
                cell.append(c);
            }
            if (cell.length() > 10000 || row.size() > 20 || rows.size() > 1001)
                throw new IllegalArgumentException("CSV exceeds row or field limits");
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote");
        if (cell.length() > 0 || !row.isEmpty() || closed) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private record MemoryFile(String filename, byte[] bytes) implements MultipartFile {
        public String getName() {
            return "resume";
        }

        public String getOriginalFilename() {
            return filename;
        }

        public String getContentType() {
            return "application/octet-stream";
        }

        public boolean isEmpty() {
            return bytes.length == 0;
        }

        public long getSize() {
            return bytes.length;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
