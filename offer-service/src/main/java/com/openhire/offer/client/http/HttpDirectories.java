package com.openhire.offer.client.http;

import com.openhire.offer.domain.OfferTypes.Band;
import com.openhire.offer.service.port.OfferPorts.ApplicationDirectory;
import com.openhire.offer.service.port.OfferPorts.ApplicationInfo;
import com.openhire.offer.service.port.OfferPorts.JobDirectory;
import com.openhire.offer.service.port.OfferPorts.JobInfo;
import com.openhire.platform.web.ResourceNotFoundException;
import com.openhire.support.Access;
import com.openhire.support.Records;
import com.openhire.support.ServiceClient;
import java.math.BigDecimal;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

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
                row.getString("candidateEmail"),
                row.getString("jobId"),
                row.getString("jobTitle"),
                row.getString("stage"));
    }

    @Override
    public void verifyReceipt(String tenantId, String applicationId, String receipt) {
        try {
            applications
                    .raw()
                    .post()
                    .uri("/api/v1/internal/applications/{id}/receipt-check", applicationId)
                    .body(Map.of("tenantId", tenantId, "receipt", receipt))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Forbidden
                | HttpClientErrorException.NotFound
                | HttpClientErrorException.BadRequest denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid application receipt");
        }
    }

    @Override
    public JobInfo job(String tenantId, String jobId) {
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
        return jobInfo(jobId, job);
    }

    /** Nested JSON objects arrive as plain maps, so the band is read through the Records helpers. */
    static JobInfo jobInfo(String jobId, Map<String, ?> job) {
        var band = Records.doc(job.get("salaryBand"));
        Band parsed = null;
        if (band.get("min") instanceof Number min
                && band.get("max") instanceof Number max
                && band.getString("currency") != null)
            parsed = new Band(band.getString("currency"), decimal(min), decimal(max));
        return new JobInfo(
                jobId,
                Records.text(job, "title"),
                Records.text(job, "location"),
                Records.text(job, "hiringManager"),
                parsed);
    }

    private static BigDecimal decimal(Number value) {
        return new BigDecimal(value.toString());
    }
}
