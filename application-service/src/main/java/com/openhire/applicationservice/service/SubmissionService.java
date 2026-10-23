package com.openhire.applicationservice.service;

import static com.openhire.support.Records.*;

import com.openhire.applicationservice.messaging.MatchingCommandContract;
import com.openhire.applicationservice.service.port.ConsentSource;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@Service
public class SubmissionService {
    private final MongoTemplate mongo;
    private final ServiceClient jobs, candidates, resumes;
    private final ObjectMapper json;
    private final ConsentTexts consents;
    private final AuditRecorder audit;

    public SubmissionService(
            MongoTemplate mongo,
            Access access,
            ObjectMapper json,
            @Value("${openhire.job.base-url}") String jobUrl,
            @Value("${openhire.candidate-url:http://localhost:8085}") String candidateUrl,
            @Value("${openhire.resume-url:http://localhost:8086}") String resumeUrl,
            ConsentTexts consents,
            AuditRecorder audit) {
        this.mongo = mongo;
        this.json = json;
        this.jobs = new ServiceClient(jobUrl, access.secret());
        this.candidates = new ServiceClient(candidateUrl, access.secret());
        this.resumes = new ServiceClient(resumeUrl, access.secret());
        this.consents = consents;
        this.audit = audit;
    }

    public static String source(String source) {
        return switch (source == null ? "" : source.trim().toUpperCase(Locale.ROOT)) {
            case "CAREER", "CAREER PAGE" -> "CAREER";
            case "AGENCY" -> "AGENCY";
            case "REFERRAL" -> "REFERRAL";
            case "INTERNAL" -> "INTERNAL";
            case "PUBLIC_API" -> "PUBLIC_API";
            case "", "RECRUITER", "RECRUITER SOURCED", "EVENT" -> "RECRUITER";
            default -> throw new IllegalArgumentException("Unknown application source");
        };
    }

    public Query key(String tenant, String id) {
        return Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(id));
    }

    public Document get(String tenant, String id) {
        var row = mongo.findOne(key(tenant, id), Document.class, "submissions");
        if (row == null) throw missing();
        return row;
    }

    public Document job(String tenant, String id) {
        return jobs.raw()
                .get()
                .uri("/api/v1/jobs/internal/{id}/intake", id)
                .header("X-Tenant-Id", tenant)
                .retrieve()
                .body(Document.class);
    }

    public ConsentSource.Consent consent(String tenant) {
        return consents.active(tenant);
    }

    public String consentVersion(String tenant) {
        return consents.version(tenant);
    }

    public Document submit(String tenant, String actor, String key, Document input, MultipartFile file)
            throws Exception {
        if (key == null || key.isBlank() || key.length() > 200)
            throw new IllegalArgumentException("Idempotency-Key is required (max 200)");
        String jobId = required(input, "jobId", 100),
                name = required(input, "name", 200),
                email = required(input, "email", 254).toLowerCase(Locale.ROOT),
                src = source(text(input, "source"));
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Invalid email");
        if (!Boolean.parseBoolean(text(input, "processingConsent")))
            throw new IllegalArgumentException("Processing consent is required");
        String consentVersion = consentVersion(tenant);
        if (!consentVersion.equals(text(input, "consentVersion")))
            throw new IllegalArgumentException("Review the current consent text before submitting");
        String external = text(input, "externalReference");
        if (external.length() > 200) throw new IllegalArgumentException("External reference is too long");
        String id = hash(
                tenant,
                actor,
                src,
                external.isBlank() ? "idempotency" : "external",
                external.isBlank() ? key : external);
        byte[] bytes = file == null || file.isEmpty() ? null : file.getBytes();
        if (bytes != null && bytes.length > 10 * 1024 * 1024)
            throw new IllegalArgumentException("Resume exceeds 10 MB");
        var normalized = new TreeMap<String, Object>();
        normalized.put("jobId", jobId);
        normalized.put("name", name);
        normalized.put("email", email);
        normalized.put("source", src);
        for (String field : List.of("phone", "owner", "consentVersion", "externalReference"))
            normalized.put(field, text(input, field).trim());
        normalized.put("answers", new TreeMap<>(doc(input.get("answers"))));
        normalized.put(
                "resumeDigest",
                bytes == null
                        ? ""
                        : HexFormat.of()
                                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(bytes)));
        String fingerprint = hash(json.writeValueAsString(normalized));
        var existing = mongo.findOne(key(tenant, id), Document.class, "submissions");
        if (existing == null) {
            var job = job(tenant, jobId);
            if (!"OPEN".equals(text(job, "status"))) throw conflict("Job is not open");
            var record = new Document("_id", id)
                    .append("tenantId", tenant)
                    .append("actorId", actor)
                    .append("source", src)
                    .append("fingerprint", fingerprint)
                    .append("jobId", jobId)
                    .append("jobTitle", text(job, "title"))
                    .append("jobSnapshot", doc(job.get("currentVersion")))
                    .append("name", name)
                    .append("email", email)
                    .append("phone", text(input, "phone"))
                    .append("owner", text(input, "owner"))
                    .append("consentVersion", consentVersion)
                    .append("processingConsent", true)
                    .append("externalReference", external)
                    .append("answers", doc(input.get("answers")))
                    .append("stage", "APPLIED")
                    .append("revision", 0L)
                    .append("submittedAt", now())
                    .append("updatedAt", now())
                    .append("processingState", bytes == null ? "PENDING" : "AWAITING_UPLOAD")
                    .append("attempts", 0)
                    .append("projectionDirty", true)
                    .append(
                            "_events",
                            List.of(event(
                                    "ApplicationSubmitted",
                                    tenant,
                                    "Application",
                                    id,
                                    Map.of("jobId", jobId, "source", src))));
            if (src.equals("CAREER")) record.put("receiptHash", hash(required(input, "receiptToken", 200)));
            try {
                mongo.insert(record, "submissions");
                existing = record;
                audit.record(AuditRecord.of(tenant, "CONSENT_GRANTED")
                        .actor(AuditRecord.ActorType.CANDIDATE, id, null)
                        .resource("Application", id)
                        .about(null, id)
                        .detail("purpose", "PROCESSING")
                        .detail("consentVersion", consentVersion)
                        .build());
            } catch (DuplicateKeyException ignored) {
                existing = get(tenant, id);
            }
        }
        if (!fingerprint.equals(existing.getString("fingerprint")))
            throw conflict("Idempotency key or external reference already used for different data");
        if (bytes != null && "AWAITING_UPLOAD".equals(existing.getString("processingState"))) {
            var waiting = key(tenant, id)
                    .addCriteria(Criteria.where("processingState").is("AWAITING_UPLOAD"));
            try {
                String resumeId = upload(tenant, id, file.getOriginalFilename(), bytes);
                mongo.updateFirst(
                        waiting,
                        new Update()
                                .set("resumeId", resumeId)
                                .set("processingState", "PENDING")
                                .set("stage", "APPLIED")
                                .set("projectionDirty", true)
                                .set("updatedAt", now())
                                .unset("attentionCode")
                                .unset("detail")
                                .inc("revision", 1),
                        "submissions");
            } catch (Exception failure) {
                mongo.updateFirst(
                        waiting,
                        new Update()
                                .set("stage", "NEEDS_ATTENTION")
                                .set("attentionCode", "UPLOAD_FAILED")
                                .set(
                                        "detail",
                                        "Resume upload did not complete; retry the original request or upload a replacement")
                                .set("projectionDirty", true)
                                .set("updatedAt", now())
                                .inc("revision", 1),
                        "submissions");
                throw failure;
            }
        }
        return get(tenant, id);
    }

    private String upload(String tenant, String application, String filename, byte[] bytes) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("tenantId", tenant);
        body.add("applicationId", application);
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename == null ? "resume" : filename;
            }
        });
        return text(
                resumes.raw()
                        .post()
                        .uri("/api/v1/internal/resumes")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(Document.class),
                "id");
    }

    public void replaceResume(String tenant, String id, MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
            throw new IllegalArgumentException("Resume must be 1 byte to 10 MB");
        var row = get(tenant, id);
        if (Set.of("REJECTED", "WITHDRAWN", "HIRED").contains(text(row, "stage")))
            throw conflict("Application is closed");
        String resumeId = upload(tenant, id, file.getOriginalFilename(), file.getBytes());
        if (mongo.updateFirst(
                                key(tenant, id)
                                        .addCriteria(Criteria.where("revision").is(row.get("revision"))),
                                new Update()
                                        .set("resumeId", resumeId)
                                        .set("processingState", "PENDING")
                                        .set("stage", "APPLIED")
                                        .set("projectionDirty", true)
                                        .set("attempts", 0)
                                        .set("updatedAt", now())
                                        .unset("assessment")
                                        .unset("score")
                                        .unset("detail")
                                        .unset("attentionCode")
                                        .set("lastDecision", false)
                                        .inc("revision", 1)
                                        .unset("lease"),
                                "submissions")
                        .getMatchedCount()
                == 0) throw conflict("Application changed; reload before replacing resume");
    }

    public List<Document> list(String tenant, String jobId, String actor, String status, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100)
            throw new IllegalArgumentException("Invalid pagination");
        var c = Criteria.where("tenantId").is(tenant);
        if (jobId != null) c = c.and("jobId").is(jobId);
        if (actor != null) c = c.and("actorId").is(actor);
        if (status != null) c = c.and("stage").is(status);
        return mongo.find(
                Query.query(c)
                        .with(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Order.desc("submittedAt"),
                                org.springframework.data.domain.Sort.Order.asc("_id")))
                        .skip((long) page * size)
                        .limit(size),
                Document.class,
                "submissions");
    }

    public Document card(Document row) {
        var result = new Document("applicationId", row.getString("_id"))
                .append("candidateId", text(row, "candidateId"))
                .append("candidateName", text(row, "name"))
                .append("candidateTitle", "")
                .append("candidateLocation", "")
                .append("experienceYears", 0)
                .append("jobId", text(row, "jobId"))
                .append("jobTitle", text(row, "jobTitle"))
                .append("source", text(row, "source"))
                .append("stage", text(row, "stage"))
                .append("score", row.get("score"))
                .append("updatedAt", text(row, "updatedAt"))
                .append("aiPending", false)
                .append("detail", text(row, "detail"))
                .append("decidedBy", text(row, "decidedBy"));
        if (!row.containsKey("score")) result.remove("score");
        if (Boolean.TRUE.equals(row.get("integrityFlag"))) result.append("integrityFlag", true);
        return result;
    }

    /** The human hire decision closes the interview stage: HIRE moves to OFFER, NO_HIRE rejects. */
    public Document decide(String tenant, String id, String actor, String actorName, String decision, String reason) {
        if (!Set.of("HIRE", "NO_HIRE").contains(decision))
            throw new IllegalArgumentException("decision must be HIRE or NO_HIRE");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reason is required for the record");
        var row = get(tenant, id);
        String stage = text(row, "stage");
        if (!Set.of("INTERVIEWING", "DECISION_PENDING").contains(stage))
            throw conflict("A hire decision can only be recorded during interviews");
        String target = decision.equals("HIRE") ? "OFFER" : "REJECTED";
        long rev = ((Number) row.get("revision")).longValue();
        var recorded = new Document("decision", decision)
                .append("decidedBy", actor)
                .append("decidedByName", actorName)
                .append("reason", reason.trim())
                .append("at", now());
        var update = new Update()
                .set("stage", target)
                .set("hireDecision", recorded)
                .set("updatedAt", now())
                .set("decidedBy", actor)
                .set("projectionDirty", true)
                .set("previousStage", stage)
                .set("lastDecision", target.equals("REJECTED"))
                .inc("revision", 1)
                .push(
                        "decisions",
                        new Document("from", stage)
                                .append("to", target)
                                .append("actor", actor)
                                .append("reason", reason.trim())
                                .append("at", now()));
        update.push("_events")
                .each(
                        event(
                                "ApplicationDecisionRecorded",
                                tenant,
                                "Application",
                                id,
                                Map.of("decision", decision, "actorId", actor)),
                        event(
                                "ApplicationStageChanged",
                                tenant,
                                "Application",
                                id,
                                Map.of("from", stage, "to", target, "actorId", actor)));
        if (mongo.updateFirst(
                                key(tenant, id)
                                        .addCriteria(Criteria.where("revision").is(rev)),
                                update,
                                "submissions")
                        .getMatchedCount()
                == 0) throw conflict("Application changed; reload");
        audit.record(AuditRecord.of(tenant, "HIRE_DECISION_RECORDED")
                .user(actor, actorName)
                .resource("Application", id)
                .about(text(row, "candidateId"), id)
                .detail("decision", decision)
                .build());
        return get(tenant, id);
    }

    public Document decision(String tenant, String id) {
        var row = get(tenant, id);
        var recorded = doc(row.get("hireDecision"));
        var result = new Document("applicationId", id)
                .append("decision", recorded.isEmpty() ? null : recorded.getString("decision"));
        if (!recorded.isEmpty())
            result.append(
                            "decidedBy",
                            text(recorded, "decidedByName").isBlank()
                                    ? text(recorded, "decidedBy")
                                    : text(recorded, "decidedByName"))
                    .append("reason", recorded.getString("reason"));
        return result;
    }

    public static final List<String> STAGES = List.of(
            "APPLIED",
            "NEEDS_ATTENTION",
            "AI_ASSESSED",
            "SHORTLISTED",
            "INTERVIEWING",
            "DECISION_PENDING",
            "OFFER",
            "HIRED",
            "REJECTED",
            "WITHDRAWN");

    /** A person moves an application; audited as shortlisted, rejected (with reason code) or stage changed. */
    public Document move(String tenant, String id, String actor, String target, String reason, Long expected) {
        return move(tenant, id, actor, target, reason, expected, null);
    }

    public Document move(
            String tenant, String id, String actor, String target, String reason, Long expected, String reasonCode) {
        var row = get(tenant, id);
        String from = text(row, "stage");
        var moved = transition(tenant, id, actor, target, reason, expected, row);
        var record = AuditRecord.of(
                        tenant,
                        switch (target) {
                            case "SHORTLISTED" -> "APPLICATION_SHORTLISTED";
                            case "REJECTED" -> "APPLICATION_REJECTED";
                            default -> "APPLICATION_STAGE_CHANGED";
                        })
                .user(actor, null)
                .resource("Application", id)
                .about(text(row, "candidateId"), id);
        if (target.equals("REJECTED")) record.detail("reasonCode", reasonCode == null ? "UNSPECIFIED" : reasonCode);
        else if (!target.equals("SHORTLISTED")) record.detail("from", from).detail("to", target);
        audit.record(record.build());
        return moved;
    }

    /** The applicant withdraws through their receipt link. */
    public Document withdraw(String tenant, String id, String actor, String reason) {
        var row = get(tenant, id);
        String from = text(row, "stage");
        var moved = transition(tenant, id, actor, "WITHDRAWN", reason, null, row);
        audit.record(AuditRecord.of(tenant, "APPLICATION_WITHDRAWN")
                .candidate(text(row, "candidateId").isBlank() ? id : text(row, "candidateId"))
                .resource("Application", id)
                .about(null, id)
                .detail("from", from)
                .build());
        return moved;
    }

    private Document transition(
            String tenant, String id, String actor, String target, String reason, Long expected, Document row) {
        String stage = text(row, "stage");
        if (!STAGES.contains(target)
                || stage.equals(target)
                || Set.of("HIRED", "REJECTED", "WITHDRAWN").contains(stage))
            throw conflict("Invalid application transition");
        if (Set.of("APPLIED", "NEEDS_ATTENTION", "AI_ASSESSED").contains(target))
            throw conflict("Processing stages are owned by the processing workflow");
        boolean valid = Set.of("REJECTED", "WITHDRAWN").contains(target)
                || switch (stage) {
                    case "AI_ASSESSED" -> target.equals("SHORTLISTED");
                    case "SHORTLISTED" -> target.equals("INTERVIEWING");
                    case "INTERVIEWING" -> target.equals("DECISION_PENDING");
                    case "DECISION_PENDING" -> target.equals("OFFER");
                    case "OFFER" -> target.equals("HIRED");
                    default -> false;
                };
        if (!valid) throw conflict("Transition not allowed from " + stage);
        if (target.equals("REJECTED") && reason.isBlank())
            throw new IllegalArgumentException("Rejection reason is required");
        long rev = ((Number) row.get("revision")).longValue();
        if (expected != null && expected != rev)
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "Reload application");
        var update = new Update()
                .set("stage", target)
                .set("updatedAt", now())
                .set("decidedBy", actor)
                .set("projectionDirty", true)
                .set("previousStage", stage)
                .set("previousProcessingState", text(row, "processingState"))
                .set("lastDecision", Set.of("SHORTLISTED", "REJECTED").contains(target))
                .set("processingState", "COMPLETE")
                .unset("lease")
                .inc("revision", 1)
                .push(
                        "decisions",
                        new Document("from", stage)
                                .append("to", target)
                                .append("actor", actor)
                                .append("reason", reason)
                                .append("at", now()))
                .push(
                        "_events",
                        event(
                                "ApplicationStageChanged",
                                tenant,
                                "Application",
                                id,
                                Map.of("from", stage, "to", target, "actorId", actor)));
        if (mongo.updateFirst(
                                key(tenant, id)
                                        .addCriteria(Criteria.where("revision").is(rev)),
                                update,
                                "submissions")
                        .getMatchedCount()
                == 0) throw conflict("Application changed; reload");
        return get(tenant, id);
    }

    public void undo(String tenant, String id, String actor) {
        var row = get(tenant, id);
        if (!Boolean.TRUE.equals(row.get("lastDecision")) || !actor.equals(text(row, "decidedBy")))
            throw conflict("Only the latest decision by this actor can be undone");
        String from = text(row, "stage");
        var q = key(tenant, id).addCriteria(Criteria.where("revision").is(row.get("revision")));
        var update = new Update()
                .set("stage", text(row, "previousStage"))
                .set("lastDecision", false)
                .set("projectionDirty", true)
                .set("updatedAt", now())
                .inc("revision", 1)
                .push(
                        "_events",
                        event("ApplicationDecisionUndone", tenant, "Application", id, Map.of("actorId", actor)));
        if ("APPLIED".equals(text(row, "previousStage"))) {
            update.set(
                            "processingState",
                            "AWAITING_UPLOAD".equals(text(row, "previousProcessingState"))
                                    ? "AWAITING_UPLOAD"
                                    : "PENDING")
                    .set("attempts", 0)
                    .unset("lease");
        }
        if (mongo.updateFirst(q, update, "submissions").getMatchedCount() == 0)
            throw conflict("Application changed; reload");
        audit.record(AuditRecord.of(tenant, "DECISION_UNDONE")
                .user(actor, null)
                .resource("Application", id)
                .about(text(row, "candidateId"), id)
                .detail("from", from)
                .detail("to", text(row, "previousStage"))
                .build());
    }

    public void reviewEligibility(String tenant, String id, String actor, String reason) {
        var row = get(tenant, id);
        if (!"NEEDS_ATTENTION".equals(text(row, "stage")) || !"KNOCKOUT_REVIEW".equals(text(row, "attentionCode")))
            throw conflict("Application is not awaiting eligibility review");
        var query = key(tenant, id).addCriteria(Criteria.where("revision").is(row.get("revision")));
        var update = new Update()
                .set("eligibilityApproved", true)
                .set("processingState", "PENDING")
                .set("stage", "APPLIED")
                .set("projectionDirty", true)
                .set("updatedAt", now())
                .set("attempts", 0)
                .unset("lease")
                .inc("revision", 1)
                .push(
                        "decisions",
                        new Document("action", "ELIGIBILITY_APPROVED")
                                .append("actor", actor)
                                .append("reason", reason)
                                .append("at", now()))
                .push(
                        "_events",
                        event("ApplicationEligibilityReviewed", tenant, "Application", id, Map.of("actorId", actor)));
        if (mongo.updateFirst(query, update, "submissions").getMatchedCount() == 0)
            throw conflict("Application changed; reload");
        audit.record(AuditRecord.of(tenant, "ELIGIBILITY_REVIEWED")
                .user(actor, null)
                .resource("Application", id)
                .about(text(row, "candidateId"), id)
                .detail("outcome", "APPROVED")
                .build());
    }

    /** Audited once, when the last row of a bulk import has settled. */
    public void importCompleted(String tenant, String importId, String actor, Document totals) {
        var record =
                AuditRecord.of(tenant, "IMPORT_COMPLETED").user(actor, null).resource("Import", importId);
        for (String count : List.of("total", "imported", "skippedNoConsent", "failed"))
            if (totals.get(count) instanceof Number number) record.detail(count, number.longValue());
        audit.record(record.build());
    }

    public static final Set<String> OVERRIDE_REASONS =
            Set.of("UNDERWEIGHTED_EVIDENCE", "OVERWEIGHTED_GAP", "CONTEXT_NOT_IN_RESUME", "OTHER");

    public void overrideScore(String tenant, String id, String actor, int score, String reason, String reasonCode) {
        var row = get(tenant, id);
        if (!row.containsKey("assessment")) throw conflict("Assessment is not ready");
        if (score < 0 || score > 100) throw new IllegalArgumentException("Score must be 0..100");
        var query = key(tenant, id).addCriteria(Criteria.where("revision").is(row.get("revision")));
        Object previousScore = row.get("score");
        var update = new Update()
                // The pipeline ranks by the top-level score; the AI's own score stays in assessment.score beside the
                // override.
                .set("score", score)
                .set(
                        "assessment.overridden",
                        Map.of("by", actor, "newScore", score, "reason", reason, "reasonCode", reasonCode))
                .set("projectionDirty", true)
                .set("updatedAt", now())
                .inc("revision", 1)
                .push(
                        "decisions",
                        new Document("action", "SCORE_OVERRIDE")
                                .append("actor", actor)
                                .append("reason", reason)
                                .append("reasonCode", reasonCode)
                                .append("at", now())
                                .append("score", score))
                .push(
                        "_events",
                        event(
                                "AssessmentOverridden",
                                tenant,
                                "Application",
                                id,
                                Map.of("actorId", actor, "score", score, "reasonCode", reasonCode)));
        if (mongo.updateFirst(query, update, "submissions").getMatchedCount() == 0)
            throw conflict("Application changed; reload");
        var record = AuditRecord.of(tenant, "ASSESSMENT_OVERRIDDEN")
                .user(actor, null)
                .resource("Application", id)
                .about(text(row, "candidateId"), id)
                .detail("reasonCode", reasonCode)
                .detail("newScore", score);
        if (previousScore instanceof Number previous) record.detail("previousScore", previous.intValue());
        audit.record(record.build());
    }

    public void retry(String tenant, String id) {
        var row = get(tenant, id);
        if ("AI_ASSESSED".equals(text(row, "stage"))) {
            rescore(tenant, row);
            return;
        }
        if (!"NEEDS_ATTENTION".equals(text(row, "stage"))) throw conflict("Application does not need retry");
        if (!text(row, "resumeId").isBlank()) {
            var resume =
                    resumes.get("/api/v1/internal/resumes/{id}?tenantId={tenant}", row.getString("resumeId"), tenant);
            if ("FAILED".equals(text(resume, "status")))
                resumes.raw()
                        .post()
                        .uri("/api/v1/internal/resumes/{id}/retry?tenantId={tenant}", row.getString("resumeId"), tenant)
                        .retrieve()
                        .toBodilessEntity();
            if ("REJECTED".equals(text(resume, "status")))
                throw conflict("Resume was rejected; upload a clean replacement");
        } else throw conflict("Upload a resume before retrying");
        if (mongo.updateFirst(
                                key(tenant, id)
                                        .addCriteria(Criteria.where("revision").is(row.get("revision"))),
                                new Update()
                                        .set("processingState", "PENDING")
                                        .set("stage", "APPLIED")
                                        .set("attempts", 0)
                                        .set("projectionDirty", true)
                                        .set("updatedAt", now())
                                        .unset("lease")
                                        .inc("revision", 1),
                                "submissions")
                        .getMatchedCount()
                == 0) throw conflict("Application changed; reload before retrying");
    }

    /** Asks the AI workers for a fresh assessment; the stage and the stored assessment stay as they are meanwhile. */
    private void rescore(String tenant, Document row) {
        if (text(row, "resumeId").isBlank()) throw conflict("Upload a resume before retrying");
        if (mongo.updateFirst(
                                key(tenant, row.getString("_id"))
                                        .addCriteria(Criteria.where("revision").is(row.get("revision"))),
                                new Update()
                                        .set("updatedAt", now())
                                        .inc("revision", 1)
                                        .push(MatchingCommandContract.FIELD, MatchingCommands.command(tenant, row)),
                                "submissions")
                        .getMatchedCount()
                == 0) throw conflict("Application changed; reload before retrying");
    }

    @Scheduled(fixedDelay = 1000)
    public void process() {
        for (int count = 0; count < 20; count++) {
            String lease = UUID.randomUUID().toString();
            var criteria = new Criteria()
                    .orOperator(
                            Criteria.where("processingState").is("PENDING"),
                            Criteria.where("processingState")
                                    .is("WAITING")
                                    .and("nextAttempt")
                                    .lte(now()),
                            Criteria.where("processingState")
                                    .is("PROCESSING")
                                    .and("leaseUntil")
                                    .lt(now()));
            var row = mongo.findAndModify(
                    Query.query(criteria),
                    new Update()
                            .set("processingState", "PROCESSING")
                            .set("lease", lease)
                            .set("leaseUntil", Instant.now().plusSeconds(120).toString()),
                    FindAndModifyOptions.options().returnNew(true),
                    Document.class,
                    "submissions");
            if (row == null) break;
            String tenant = row.getString("tenantId"), id = row.getString("_id");
            var owned = key(tenant, id).addCriteria(Criteria.where("lease").is(lease));
            try {
                if (text(row, "candidateId").isBlank()) {
                    var request = new Document(row);
                    request.put("applicationId", id);
                    String candidateId = text(candidates.post("/api/v1/internal/candidates/resolve", request), "id");
                    row.put("candidateId", candidateId);
                    mongo.updateFirst(owned, new Update().set("candidateId", candidateId), "submissions");
                }
                if (text(row, "resumeId").isBlank()) {
                    attention(owned, row, "MISSING_RESUME", "Upload a resume to continue");
                    continue;
                }
                var resume = resumes.get(
                        "/api/v1/internal/resumes/{id}?tenantId={tenant}", row.getString("resumeId"), tenant);
                String status = text(resume, "status");
                if (Set.of("PENDING", "PROCESSING").contains(status)) {
                    mongo.updateFirst(
                            owned,
                            new Update()
                                    .set("processingState", "WAITING")
                                    .set(
                                            "nextAttempt",
                                            Instant.now().plusSeconds(3).toString())
                                    .unset("lease"),
                            "submissions");
                    continue;
                }
                if (!status.equals("DONE")) {
                    attention(owned, row, text(resume, "error"), "Resume processing needs attention");
                    continue;
                }
                var assessment = screen(row, text(resume, "text"));
                boolean knockout = Boolean.TRUE.equals(assessment.remove("knockoutFailed"));
                if (knockout) {
                    attention(owned, row, "KNOCKOUT_REVIEW", "Required eligibility answer needs human review");
                    continue;
                }
                mongo.updateFirst(
                        owned,
                        new Update()
                                .set("assessment", assessment)
                                .set("score", assessment.get("score"))
                                .set("resumeName", text(resume, "name"))
                                .set("stage", "AI_ASSESSED")
                                .set("processingState", "COMPLETE")
                                .set("detail", "Keyword screening complete; recruiter decision required")
                                .set("projectionDirty", true)
                                .set("updatedAt", now())
                                .inc("revision", 1)
                                .unset("lease")
                                .push(
                                        "_events",
                                        event(
                                                "ApplicationScreened",
                                                tenant,
                                                "Application",
                                                id,
                                                Map.of("score", assessment.get("score"))))
                                .push(MatchingCommandContract.FIELD, MatchingCommands.command(tenant, row)),
                        "submissions");
            } catch (Exception exception) {
                int attempts = ((Number) row.getOrDefault("attempts", 0)).intValue() + 1;
                if (attempts >= 3)
                    attention(
                            owned,
                            row,
                            "DEPENDENCY_UNAVAILABLE",
                            "Processing dependency unavailable; retry after recovery");
                else
                    mongo.updateFirst(
                            owned,
                            new Update()
                                    .set("attempts", attempts)
                                    .set("processingState", "WAITING")
                                    .set(
                                            "nextAttempt",
                                            Instant.now()
                                                    .plusSeconds(10L * attempts)
                                                    .toString())
                                    .unset("lease"),
                            "submissions");
            }
        }
        projectPending();
    }

    private void attention(Query owned, Document row, String code, String detail) {
        mongo.updateFirst(
                owned,
                new Update()
                        .set("stage", "NEEDS_ATTENTION")
                        .set("processingState", "COMPLETE")
                        .set("attentionCode", code)
                        .set("detail", detail)
                        .set("projectionDirty", true)
                        .set("updatedAt", now())
                        .inc("revision", 1)
                        .unset("lease")
                        .push(
                                "_events",
                                event(
                                        "ApplicationNeedsAttention",
                                        row.getString("tenantId"),
                                        "Application",
                                        row.getString("_id"),
                                        Map.of("code", code))),
                "submissions");
    }

    static final String PARTIAL_LABEL = "AI unavailable, partial assessment";

    public static Document screen(Document row, String text) {
        var snapshot = doc(row.get("jobSnapshot"));
        var requirements = (List<?>) snapshot.getOrDefault("mustHaveSkills", List.of());
        var criteria = new ArrayList<Document>();
        var strengths = new ArrayList<String>();
        var gaps = new ArrayList<String>();
        int matched = 0;
        for (Object item : requirements) {
            String skill = item.toString();
            boolean found = Pattern.compile("(?i)(?<![a-z0-9])" + Pattern.quote(skill) + "(?![a-z0-9])")
                    .matcher(text)
                    .find();
            if (found) {
                matched++;
                strengths.add(skill);
            } else gaps.add(skill);
            criteria.add(new Document("id", hash(skill))
                    .append("label", skill)
                    .append("requirement", "MUST")
                    .append("weight", requirements.isEmpty() ? 0 : 100.0 / requirements.size())
                    .append("score", found ? 100 : 0)
                    .append("evidence", found ? List.of("Keyword present in extracted resume: " + skill) : List.of()));
        }
        int score = requirements.isEmpty() ? 0 : (int) Math.round(100.0 * matched / requirements.size());
        boolean knockout = false;
        var answers = doc(row.get("answers"));
        for (var question : documents(snapshot.get("knockoutQuestions")))
            if (!text(question, "requiredAnswer").equalsIgnoreCase(text(answers, text(question, "id"))))
                knockout = true;
        return new Document("applicationId", row.getString("_id"))
                .append("score", score)
                .append("confidence", "LOW")
                .append("modelVersion", "deterministic-keywords-v1")
                .append("status", "PARTIAL")
                .append("label", PARTIAL_LABEL)
                .append("jobVersion", MatchingCommands.jobVersion(row))
                .append("rubricVersion", String.valueOf(snapshot.getOrDefault("version", 1)))
                .append("criteria", criteria)
                .append("strengths", strengths)
                .append("gapsToProbe", gaps)
                .append("resumeExcerpt", text.substring(0, Math.min(text.length(), 8000)))
                .append("integrityFlags", documents(row.get("integrityFlags")))
                .append("knockoutFailed", knockout && !Boolean.TRUE.equals(row.get("eligibilityApproved")));
    }

    private void projectPending() {
        for (var row : mongo.find(
                Query.query(Criteria.where("projectionDirty")
                                .is(true)
                                .and("candidateId")
                                .exists(true))
                        .limit(50),
                Document.class,
                "submissions"))
            try {
                var projection = new Document("tenantId", row.getString("tenantId"))
                        .append("candidateId", row.getString("candidateId"))
                        .append("applicationId", row.getString("_id"))
                        .append("jobId", row.getString("jobId"))
                        .append("jobTitle", row.getString("jobTitle"))
                        .append("stage", row.getString("stage"))
                        .append("appliedAt", row.getString("submittedAt"))
                        .append("updatedAt", row.getString("updatedAt"))
                        .append("revision", row.get("revision"))
                        .append("resumeId", text(row, "resumeId"))
                        .append("resumeName", text(row, "resumeName"));
                var result = candidates.post("/api/v1/internal/candidates/project", projection);
                mongo.updateFirst(
                        key(row.getString("tenantId"), row.getString("_id"))
                                .addCriteria(Criteria.where("revision").is(row.get("revision"))),
                        new Update().set("projectionDirty", false).set("candidateId", text(result, "id")),
                        "submissions");
            } catch (Exception ignored) {
                /* durable projectionDirty is retried on next poll */
            }
    }

    public MongoTemplate store() {
        return mongo;
    }
}
