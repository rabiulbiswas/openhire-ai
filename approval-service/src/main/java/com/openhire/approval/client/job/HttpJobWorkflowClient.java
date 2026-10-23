package com.openhire.approval.client.job;

import com.openhire.approval.service.port.JobWorkflowClient;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpJobWorkflowClient implements JobWorkflowClient {
    private final RestClient client;
    private final String secret;

    public HttpJobWorkflowClient(
            RestClient.Builder builder,
            @Value("${openhire.job.base-url:http://localhost:8082}") String baseUrl,
            @Value("${openhire.internal-secret:local-development-secret}") String secret) {
        this.client = builder.baseUrl(baseUrl).build();
        this.secret = secret;
    }

    @Override
    public void decision(String tenantId, String jobId, String decision, String reason, String actorId) {
        client.post()
                .uri("/api/v1/jobs/internal/{id}/approval-decision", jobId)
                .header("X-Tenant-Id", tenantId)
                .header("X-Internal-Service-Secret", secret)
                .body(Map.of(
                        "decision", decision,
                        "reason", reason == null ? "" : reason,
                        "actorId", actorId == null ? "approval-service" : actorId))
                .retrieve()
                .toBodilessEntity();
    }
}
