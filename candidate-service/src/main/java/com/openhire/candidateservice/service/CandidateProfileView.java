package com.openhire.candidateservice.service;

import static com.openhire.support.Records.*;

import com.openhire.support.Access;
import com.openhire.support.ServiceClient;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Candidate 360 read model. Enrich missing fields from the linked, processed resume; never rewrite identity. */
@Service
public class CandidateProfileView {
    private final CandidateService candidates;
    private final ServiceClient resumes;

    @Autowired
    public CandidateProfileView(
            CandidateService candidates,
            Access access,
            @Value("${openhire.resume-url:http://localhost:8086}") String url) {
        this(candidates, new ServiceClient(url, access.secret()));
    }

    CandidateProfileView(CandidateService candidates, ServiceClient resumes) {
        this.candidates = candidates;
        this.resumes = resumes;
    }

    public Document view(String tenant, String id) {
        var row = candidates.view(tenant, id);
        row.putIfAbsent("skills", List.of());
        row.putIfAbsent("experience", List.of());
        if (Boolean.TRUE.equals(row.get("erased")) || (!missing(row, "skills") && !missing(row, "experience")))
            return row;
        var documents = documents(row.get("documents")).stream()
                .sorted(Comparator.comparing((Document document) -> text(document, "uploadedAt"))
                        .reversed())
                .toList();
        if (documents.isEmpty()) return row;
        var document = documents.getFirst();
        String resumeId = text(document, "id");
        if (resumeId.isBlank()) return row;
        Document resume;
        try {
            resume = resumes.get("/api/v1/internal/resumes/{id}?tenantId={tenant}", resumeId, tenant);
        } catch (org.springframework.web.client.RestClientException unavailable) {
            row.put("profileResumeStatus", "UNAVAILABLE");
            return row;
        }
        boolean belongs = resume != null
                && tenant.equals(text(resume, "tenantId"))
                && resumeId.equals(text(resume, "id"))
                && documents(row.get("applications")).stream()
                        .anyMatch(application ->
                                text(application, "applicationId").equals(text(resume, "applicationId")));
        if (!belongs) {
            row.put("profileResumeStatus", "UNAVAILABLE");
            return row;
        }
        if (!"DONE".equals(text(resume, "status"))) {
            row.put(
                    "profileResumeStatus",
                    Set.of("PENDING", "PROCESSING").contains(text(resume, "status")) ? "PROCESSING" : "UNAVAILABLE");
            return row;
        }
        // Re-read after the network request: concurrent manual edits and privacy erasure take precedence.
        row = candidates.view(tenant, id);
        if (Boolean.TRUE.equals(row.get("erased"))) return row;
        boolean stillLinked =
                documents(row.get("documents")).stream().anyMatch(link -> resumeId.equals(text(link, "id")))
                        && documents(row.get("applications")).stream()
                                .anyMatch(app -> text(resume, "applicationId").equals(text(app, "applicationId")));
        if (!stillLinked) return row;
        var evidence = ResumeProfileEvidence.extract(resume);
        var provenance = new Document(doc(row.get("provenance")));
        var source = new Document("source", "RESUME")
                .append("resumeId", resumeId)
                .append("resumeName", text(resume, "name"))
                .append("recordedAt", text(resume, "updatedAt"))
                .append("applicationId", text(resume, "applicationId"));
        if (resume.get("version") instanceof Number version) source.put("resumeVersion", version);
        for (String field : List.of("skills", "experience")) {
            if (missing(row, field) && !documents(evidence.get(field)).isEmpty()) {
                row.put(field, evidence.get(field));
                provenance.put(field, source);
            }
        }
        row.put("provenance", provenance);
        row.put("profileResumeStatus", "AVAILABLE");
        return row;
    }

    private static boolean missing(Document row, String field) {
        String source = text(doc(doc(row.get("provenance")).get(field)), "source");
        return documents(row.get(field)).isEmpty()
                && !Set.of("RECRUITER_PROFILE_EDIT", "CANDIDATE_EDIT", "SELF_REPORTED")
                        .contains(source);
    }
}
