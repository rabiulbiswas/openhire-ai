package com.openhire.analytics.adapter.out.http;

import com.openhire.analytics.application.port.AnalyticsPorts.ApplicationDirectory;
import com.openhire.support.Access;
import com.openhire.support.Records;
import com.openhire.support.ServiceClient;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** Reads job, title, source and submission time only; candidate fields of the projection are never touched. */
@Component
class HttpApplicationDirectory implements ApplicationDirectory {
    private final ServiceClient applications;

    HttpApplicationDirectory(Access access, @Value("${openhire.application-url}") String applicationUrl) {
        applications = new ServiceClient(applicationUrl, access.secret());
    }

    @Override
    public Optional<ApplicationInfo> lookup(String tenantId, String applicationId) {
        Document row;
        try {
            row = applications
                    .raw()
                    .get()
                    .uri("/api/v1/internal/applications/{id}/projection?tenantId={tenant}", applicationId, tenantId)
                    .retrieve()
                    .body(Document.class);
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        }
        if (row == null) return Optional.empty();
        return Optional.of(new ApplicationInfo(
                Records.text(row, "jobId"),
                Records.text(row, "jobTitle"),
                Records.text(row, "source"),
                instant(Records.text(row, "appliedAt"))));
    }

    private static Instant instant(String value) {
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
