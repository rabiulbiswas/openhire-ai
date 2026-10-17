package com.openhire.applicationservice.adapter.out.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openhire.applicationservice.application.port.JobCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class HttpJobCatalog implements JobCatalog {
    private final RestClient http;

    public HttpJobCatalog(@Value("${openhire.job.base-url}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public OpenJob requireOpen(String tenant, String jobId, String token) {
        JobSnapshot job;
        try {
            var request = http.get().uri("/api/v1/jobs/{id}", jobId).header("X-Tenant-Id", tenant);
            if (token != null) request.header("Authorization", "Bearer " + token);
            job = request.retrieve().body(JobSnapshot.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
            }
            if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Job access denied");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Job lookup is unavailable; retry later");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Job lookup is unavailable; retry later");
        }
        if (job == null || job.currentVersion() == null || !jobId.equals(job.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid job response");
        }
        if (!"OPEN".equals(job.status())) throw new IllegalStateException("Job is not open for applications");
        return new OpenJob(job.id(), job.title(), job.currentVersion().version());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobSnapshot(String id, String title, String status, Version currentVersion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Version(int version) {}
}
