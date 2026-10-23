package com.openhire.interview.client.http;

import com.openhire.interview.domain.InterviewTypes.Competency;
import com.openhire.interview.domain.InterviewTypes.RoundPlan;
import com.openhire.interview.service.port.InterviewPorts.ApplicationDirectory;
import com.openhire.interview.service.port.InterviewPorts.ApplicationInfo;
import com.openhire.interview.service.port.InterviewPorts.JobDirectory;
import com.openhire.interview.service.port.InterviewPorts.JobPlan;
import com.openhire.platform.web.ResourceNotFoundException;
import com.openhire.support.Access;
import com.openhire.support.Records;
import com.openhire.support.ServiceClient;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Component
class HttpDirectories implements ApplicationDirectory, JobDirectory {
    private final ServiceClient applications;
    private final ServiceClient jobs;

    HttpDirectories(
            Access access,
            @Value("${openhire.application-url}") String applicationUrl,
            @Value("${openhire.job-url}") String jobUrl) {
        applications = new ServiceClient(applicationUrl, access.secret());
        jobs = new ServiceClient(jobUrl, access.secret());
    }

    @Override
    public ApplicationInfo application(String tenantId, String applicationId) {
        Document row;
        try {
            row = applications
                    .raw()
                    .get()
                    .uri("/api/v1/internal/applications/{id}/projection?tenantId={tenant}", applicationId, tenantId)
                    .retrieve()
                    .body(Document.class);
        } catch (HttpClientErrorException.NotFound missing) {
            throw new ResourceNotFoundException("Application " + applicationId + " was not found");
        }
        if (row == null) throw new ResourceNotFoundException("Application " + applicationId + " was not found");
        return new ApplicationInfo(
                applicationId,
                row.getString("candidateId"),
                row.getString("candidateName"),
                row.getString("jobId"),
                row.getString("jobTitle"),
                row.getString("stage"));
    }

    @Override
    public JobPlan interviewPlan(String tenantId, String jobId) {
        Document job;
        try {
            job = jobs.raw()
                    .get()
                    .uri("/api/v1/jobs/internal/{id}/intake", jobId)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(Document.class);
        } catch (HttpClientErrorException.NotFound missing) {
            throw new ResourceNotFoundException("Job " + jobId + " was not found");
        }
        if (job == null) throw new ResourceNotFoundException("Job " + jobId + " was not found");
        return planFrom(jobId, job);
    }

    /** JSON nested objects arrive as plain maps, not BSON documents, so every level is normalised. */
    static JobPlan planFrom(String jobId, Map<String, ?> job) {
        var version = Records.doc(job.get("currentVersion"));
        var rounds = Records.documents(version.get("interviewPlan")).stream()
                .map(round -> new RoundPlan(
                        round.getString("name"),
                        ((Number) round.get("durationMin")).intValue(),
                        round.get("interviewerNames") instanceof List<?> names
                                ? names.stream().map(String::valueOf).toList()
                                : List.of()))
                .toList();
        var rubric = Records.documents(version.get("rubric")).stream()
                .map(item -> new Competency(item.getString("id"), item.getString("label")))
                .toList();
        return new JobPlan(jobId, Records.text(job, "title"), rounds, rubric);
    }
}
