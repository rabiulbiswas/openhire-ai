package com.openhire.jobservice.client.application;

import com.openhire.jobservice.service.port.JobMetrics;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpJobMetrics implements JobMetrics {
    private final RestClient client;
    private final String secret;

    public HttpJobMetrics(
            @Value("${openhire.application.base-url:http://localhost:8084}") String url,
            @Value("${openhire.internal-secret}") String secret) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(url).requestFactory(requests).build();
        this.secret = secret;
    }

    public Map<String, Counts> counts(String tenantId, List<String> jobIds) {
        var result = new HashMap<String, Counts>();
        for (int offset = 0; offset < jobIds.size(); offset += 200) {
            var batch = jobIds.subList(offset, Math.min(offset + 200, jobIds.size()));
            var rows = client.post()
                    .uri("/api/v1/internal/jobs/metrics")
                    .header("X-Internal-Service-Secret", secret)
                    .header("X-Tenant-Id", tenantId)
                    .body(batch)
                    .retrieve()
                    .body(Row[].class);
            if (rows == null) throw new IllegalStateException("Application metrics response missing");
            batch.forEach(id -> result.put(id, new Counts(0, 0)));
            for (var row : rows)
                if (batch.contains(row.jobId()))
                    result.put(row.jobId(), new Counts(row.applicants(), row.shortlisted()));
        }
        return result;
    }

    public record Row(String jobId, int applicants, int shortlisted) {}
}
