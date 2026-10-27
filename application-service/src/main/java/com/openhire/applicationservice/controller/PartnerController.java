package com.openhire.applicationservice.controller;

import static com.openhire.support.Records.text;

import com.openhire.applicationservice.dto.PartnerPage;
import com.openhire.applicationservice.repository.mongo.PartnerRepository;
import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.support.Access;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/partner")
public class PartnerController {
    private final PartnerRepository repository;
    private final SubmissionService submissions;
    private final Access access;

    public PartnerController(PartnerRepository repository, SubmissionService submissions, Access access) {
        this.repository = repository;
        this.submissions = submissions;
        this.access = access;
    }

    private void requireAgency(Authentication auth) {
        if (!access.role(auth, "AGENCY_RECRUITER") && !access.staff(auth))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agency access required");
    }

    @GetMapping("/jobs/{id}")
    public Document job(Authentication auth, @PathVariable String id) {
        requireAgency(auth);
        var tenant = access.tenant(auth);
        var actor = access.actor(auth);
        if (id.length() > 100 || !repository.assigned(tenant, actor, id))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Job is not assigned to this account");
        var job = submissions.job(tenant, id);
        return new Document("id", id)
                .append("title", text(job, "title"))
                .append("location", text(job, "location"))
                .append("status", text(job, "status"));
    }

    @GetMapping("/jobs")
    public PartnerPage<Document> jobs(
            Authentication auth,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "") String before,
            @RequestParam(defaultValue = "25") int limit) {
        requireAgency(auth);
        var tenant = access.tenant(auth);
        var actor = access.actor(auth);
        var page = repository.jobs(tenant, actor, after, before, limit);
        var counts = repository.counts(
                tenant,
                actor,
                page.data().stream().map(row -> text(row, "jobId")).toList());
        return page.map(assignment -> {
            var id = text(assignment, "jobId");
            var count = counts.getOrDefault(id, new Document());
            Document job;
            try {
                job = submissions.job(tenant, id);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
                return new Document("id", id)
                        .append("title", "Job unavailable")
                        .append("location", "")
                        .append("status", "UNAVAILABLE")
                        .append("submitted", count.getOrDefault("submitted", 0))
                        .append("shortlisted", count.getOrDefault("shortlisted", 0));
            }
            return new Document("id", id)
                    .append("title", text(job, "title"))
                    .append("location", text(job, "location"))
                    .append("status", text(job, "status"))
                    .append("submitted", count.getOrDefault("submitted", 0))
                    .append("shortlisted", count.getOrDefault("shortlisted", 0));
        });
    }

    @GetMapping("/submissions")
    public PartnerPage<Document> submissions(
            Authentication auth,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "") String before,
            @RequestParam(defaultValue = "25") int limit) {
        requireAgency(auth);
        return repository
                .submissions(access.tenant(auth), access.actor(auth), after, before, limit)
                .map(row -> new Document("id", text(row, "_id"))
                        .append("name", text(row, "name"))
                        .append("jobTitle", text(row, "jobTitle"))
                        .append("stage", publicStage(text(row, "stage")))
                        .append("submittedAt", text(row, "submittedAt")));
    }

    static String publicStage(String stage) {
        return switch (stage) {
            case "SHORTLISTED" -> "SHORTLISTED";
            case "INTERVIEWING", "DECISION" -> "INTERVIEWING";
            case "OFFER" -> "OFFER";
            case "HIRED" -> "HIRED";
            case "REJECTED", "WITHDRAWN" -> "NOT_MOVING_FORWARD";
            case "AI_ASSESSED", "NEEDS_ATTENTION" -> "UNDER_REVIEW";
            default -> "RECEIVED";
        };
    }
}
