package com.openhire.interview.client.http;

import static com.openhire.support.Records.*;

import com.openhire.interview.domain.*;
import com.openhire.interview.service.InterviewAssistant;
import com.openhire.support.*;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
class HttpInterviewAssistant implements InterviewAssistant.Gateway {
    private final org.springframework.web.client.RestClient ai;
    private final ServiceClient applications, jobs, resumes;

    HttpInterviewAssistant(
            Access access,
            @Value("${openhire.ai-url:http://localhost:8092}") String aiUrl,
            @Value("${openhire.application-url}") String appUrl,
            @Value("${openhire.job-url}") String jobUrl,
            @Value("${openhire.resume-url:http://localhost:8086}") String resumeUrl) {
        var requests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(java.time.Duration.ofSeconds(3));
        requests.setReadTimeout(java.time.Duration.ofSeconds(190));
        ai = org.springframework.web.client.RestClient.builder()
                .baseUrl(aiUrl)
                .requestFactory(requests)
                .defaultHeader("X-Internal-Service-Secret", access.secret())
                .build();
        applications = new ServiceClient(appUrl, access.secret());
        jobs = new ServiceClient(jobUrl, access.secret());
        resumes = new ServiceClient(resumeUrl, access.secret());
    }

    public Map<String, Object> generate(
            String tenant, InterviewLoop loop, InterviewRound round, String mode, List<Map<String, String>> sources) {
        var context = new StringBuilder(
                "Job: " + loop.jobTitle() + "\nRound: " + round.name() + "\nCompetencies: " + round.competencies());
        try {
            if (mode.equals("QUESTIONS")) {
                var job = jobs.raw()
                        .get()
                        .uri("/api/v1/jobs/internal/{id}/intake", loop.jobId())
                        .header("X-Tenant-Id", tenant)
                        .retrieve()
                        .body(Document.class);
                var version = doc(job == null ? null : job.get("currentVersion"));
                context.append("\nJob description: ").append(clip(text(version, "description"), 4500));
                var app = applications.get(
                        "/api/v1/internal/applications/{id}/projection?tenantId={tenant}",
                        loop.applicationId(),
                        tenant);
                if (app != null && !text(app, "resumeId").isBlank()) {
                    var resume = resumes.get(
                            "/api/v1/internal/resumes/{id}?tenantId={tenant}", text(app, "resumeId"), tenant);
                    if (resume != null
                            && tenant.equals(text(resume, "tenantId"))
                            && loop.applicationId().equals(text(resume, "applicationId"))) {
                        String resumeText = text(resume, "text");
                        if (resumeText.isBlank())
                            resumeText = doc(resume.get("sections")).toJson();
                        context.append("\nResume evidence: ").append(clip(resumeText, 6000));
                    }
                }
            }
            var result = ai.post()
                    .uri("/api/v1/internal/ai/interview-assist")
                    .header("X-Tenant-Id", tenant)
                    .body(Map.of(
                            "mode",
                            mode,
                            "context",
                            clip(context.toString(), 16000),
                            "sources",
                            sources,
                            "candidateName",
                            loop.candidateName()))
                    .retrieve()
                    .body(Document.class);
            if (result == null) throw new IllegalStateException("Empty interview assistant response");
            return result;
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI assistance is unavailable. Your notes are unchanged; continue manually or retry.");
        }
    }

    @Override
    public Map<String, Object> resume(String tenant, InterviewLoop loop) {
        try {
            var app = applications.get(
                    "/api/v1/internal/applications/{id}/projection?tenantId={tenant}", loop.applicationId(), tenant);
            if (app == null || text(app, "resumeId").isBlank())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No resume is attached to this application");
            var resume = resumes.get("/api/v1/internal/resumes/{id}?tenantId={tenant}", text(app, "resumeId"), tenant);
            if (resume == null
                    || !tenant.equals(text(resume, "tenantId"))
                    || !loop.applicationId().equals(text(resume, "applicationId")))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resume not found");
            String content = text(resume, "text");
            if (content.isBlank()) content = doc(resume.get("sections")).toJson();
            return Map.of("name", text(resume, "name"), "status", text(resume, "status"), "text", clip(content, 24000));
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The resume is temporarily unavailable");
        }
    }

    private static String clip(String value, int max) {
        return value.substring(0, Math.min(value.length(), max));
    }
}
