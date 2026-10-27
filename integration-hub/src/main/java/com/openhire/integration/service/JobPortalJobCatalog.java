package com.openhire.integration.service;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class JobPortalJobCatalog {
    private final RestClient http;
    private final String secret;

    public JobPortalJobCatalog(
            @Value("${JOB_SERVICE_URL:http://job-service:8082}") String url,
            @Value("${openhire.internal-secret}") String secret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.secret = secret;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String tenant, String job) {
        return http.get()
                .uri("/api/v1/jobs/internal/{id}/intake", job)
                .header("X-Tenant-Id", tenant)
                .header("X-Internal-Service-Secret", secret)
                .retrieve()
                .body(Map.class);
    }

    public static boolean publishable(Map<String, Object> job) {
        if (job == null || !"OPEN".equals(job.get("status"))) return false;
        if (!(job.get("currentVersion") instanceof Map<?, ?> version)) return false;
        return job.get("approvedVersion") instanceof Number approved
                && version.get("version") instanceof Number current
                && approved.intValue() > 0
                && approved.intValue() == current.intValue();
    }
}
