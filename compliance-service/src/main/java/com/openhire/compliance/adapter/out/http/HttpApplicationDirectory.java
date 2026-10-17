package com.openhire.compliance.adapter.out.http;

import com.openhire.compliance.application.ComplianceExceptions;
import com.openhire.compliance.application.port.CompliancePorts.AppInfo;
import com.openhire.compliance.application.port.CompliancePorts.ApplicationDirectory;
import com.openhire.compliance.application.port.CompliancePorts.DirectoryUnavailable;
import com.openhire.support.Access;
import com.openhire.support.ServiceClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** application-service's internal projection and receipt check, with a small short-lived cache for display names. */
@Component
class HttpApplicationDirectory implements ApplicationDirectory {
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int MAX_ENTRIES = 2000;

    private record Cached(long at, Optional<AppInfo> info) {}

    private final ServiceClient applications;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    HttpApplicationDirectory(Access access, @Value("${openhire.compliance.application-url}") String applicationUrl) {
        this.applications = new ServiceClient(applicationUrl, access.secret());
    }

    @Override
    public Optional<AppInfo> application(String tenantId, String applicationId) {
        var key = tenantId + "|" + applicationId;
        var hit = cache.get(key);
        long now = System.nanoTime();
        if (hit != null && now - hit.at() < TTL.toNanos()) return hit.info();
        var info = currentApplication(tenantId, applicationId);
        if (cache.size() >= MAX_ENTRIES) cache.clear();
        cache.put(key, new Cached(now, info));
        return info;
    }

    @Override
    public Optional<AppInfo> currentApplication(String tenantId, String applicationId) {
        try {
            var row = applications
                    .raw()
                    .get()
                    .uri("/api/v1/internal/applications/{id}/projection?tenantId={tenant}", applicationId, tenantId)
                    .retrieve()
                    .body(Document.class);
            if (row == null) return Optional.empty();
            return Optional.of(new AppInfo(
                    applicationId,
                    blank(row.getString("candidateId")),
                    blank(row.getString("candidateName")),
                    blank(row.getString("jobId")),
                    blank(row.getString("jobTitle")),
                    blank(row.getString("stage")),
                    blank(row.getString("source"))));
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        } catch (RestClientException failure) {
            throw new DirectoryUnavailable("application-service is unavailable", failure);
        }
    }

    @Override
    public String verifyReceipt(String tenantId, String applicationId, String receipt) {
        try {
            var body = applications
                    .raw()
                    .post()
                    .uri("/api/v1/internal/applications/{id}/receipt-check", applicationId)
                    .body(Map.of("tenantId", tenantId, "receipt", receipt))
                    .retrieve()
                    .body(Document.class);
            var candidateId = body == null ? null : blank(body.getString("candidateId"));
            if (candidateId != null) return candidateId;
            return currentApplication(tenantId, applicationId)
                    .map(AppInfo::candidateId)
                    .filter(c -> c != null)
                    .orElseThrow(() -> new ComplianceExceptions.Forbidden("Invalid application receipt"));
        } catch (HttpClientErrorException.Forbidden
                | HttpClientErrorException.NotFound
                | HttpClientErrorException.BadRequest denied) {
            throw new ComplianceExceptions.Forbidden("Invalid application receipt");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
