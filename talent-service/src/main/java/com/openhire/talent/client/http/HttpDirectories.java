package com.openhire.talent.client.http;

import com.openhire.platform.web.ResourceNotFoundException;
import com.openhire.support.Access;
import com.openhire.support.Records;
import com.openhire.support.ServiceClient;
import com.openhire.talent.service.port.TalentPorts.ApplicationDirectory;
import com.openhire.talent.service.port.TalentPorts.ApplicationInfo;
import com.openhire.talent.service.port.TalentPorts.JobDirectory;
import com.openhire.talent.service.port.TalentPorts.JobInfo;
import com.openhire.talent.service.port.TalentPorts.ReceiptInfo;
import com.openhire.talent.service.port.TalentPorts.ReceiptRejected;
import com.openhire.talent.service.port.TalentPorts.ResumeDirectory;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** Read-only lookups in application-service, resume-processor and job-service (internal secret, tenant explicit). */
@Component
class HttpDirectories implements ApplicationDirectory, ResumeDirectory, JobDirectory {
    private final ServiceClient applications;
    private final ServiceClient resumes;
    private final ServiceClient jobs;

    HttpDirectories(
            Access access,
            @Value("${openhire.application-url}") String applicationUrl,
            @Value("${openhire.talent.resume-url}") String resumeUrl,
            @Value("${openhire.job-url}") String jobUrl) {
        applications = new ServiceClient(applicationUrl, access.secret());
        resumes = new ServiceClient(resumeUrl, access.secret());
        jobs = new ServiceClient(jobUrl, access.secret());
    }

    @Override
    public ApplicationInfo application(String tenantId, String applicationId) {
        Map<?, ?> row;
        try {
            row = applications
                    .raw()
                    .get()
                    .uri("/api/v1/internal/applications/{id}/projection?tenantId={tenant}", applicationId, tenantId)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException.NotFound missing) {
            throw new ResourceNotFoundException("Application " + applicationId + " was not found");
        }
        if (row == null) throw new ResourceNotFoundException("Application " + applicationId + " was not found");
        return applicationInfo(applicationId, row);
    }

    @Override
    public ReceiptInfo verifyReceipt(String tenantId, String applicationId, String receipt) {
        try {
            var row = applications
                    .raw()
                    .post()
                    .uri("/api/v1/internal/applications/{id}/receipt-check", applicationId)
                    .body(Map.of("tenantId", tenantId, "receipt", receipt))
                    .retrieve()
                    .body(Map.class);
            return new ReceiptInfo(
                    row == null ? null : Records.text(row, "candidateId"),
                    row == null ? null : Records.text(row, "candidateName"));
        } catch (HttpClientErrorException.Forbidden
                | HttpClientErrorException.NotFound
                | HttpClientErrorException.BadRequest denied) {
            throw new ReceiptRejected();
        }
    }

    @Override
    public Optional<String> text(String tenantId, String resumeId) {
        try {
            var row = resumes.raw()
                    .get()
                    .uri("/api/v1/internal/resumes/{id}?tenantId={tenant}", resumeId, tenantId)
                    .retrieve()
                    .body(Map.class);
            if (row == null || !"DONE".equals(row.get("status")) || !(row.get("text") instanceof String text))
                return Optional.empty();
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        }
    }

    @Override
    public JobInfo job(String tenantId, String jobId) {
        Map<?, ?> job;
        try {
            job = jobs.raw()
                    .get()
                    .uri("/api/v1/jobs/internal/{id}/intake", jobId)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException.NotFound missing) {
            throw new ResourceNotFoundException("Job " + jobId + " was not found");
        }
        if (job == null) throw new ResourceNotFoundException("Job " + jobId + " was not found");
        return jobInfo(jobId, job);
    }

    static ApplicationInfo applicationInfo(String applicationId, Map<?, ?> row) {
        return new ApplicationInfo(
                applicationId,
                blankToNull(Records.text(cast(row), "candidateId")),
                Records.text(cast(row), "candidateName"),
                Records.text(cast(row), "jobId"),
                Records.text(cast(row), "jobTitle"),
                Records.text(cast(row), "stage"),
                instant(Records.text(cast(row), "appliedAt")),
                blankToNull(Records.text(cast(row), "resumeId")));
    }

    /** Nested JSON arrives as plain maps and lists. */
    static JobInfo jobInfo(String jobId, Map<?, ?> job) {
        var version = job.get("currentVersion") instanceof Map<?, ?> map ? map : Map.of();
        var skills = new ArrayList<String>();
        if (version.get("mustHaveSkills") instanceof List<?> list)
            for (var value : list) if (value instanceof String skill) skills.add(skill);
        var criteria = new ArrayList<String>(skills);
        if (version.get("rubric") instanceof List<?> rubric)
            for (var value : rubric)
                if (value instanceof Map<?, ?> row
                        && row.get("label") instanceof String label
                        && !criteria.contains(label)) criteria.add(label);
        return new JobInfo(
                jobId,
                Records.text(cast(job), "title"),
                Records.text(cast(job), "status"),
                version.get("description") instanceof String description ? description : "",
                skills,
                criteria);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cast(Map<?, ?> value) {
        return (Map<String, ?>) value;
    }

    private static Instant instant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
