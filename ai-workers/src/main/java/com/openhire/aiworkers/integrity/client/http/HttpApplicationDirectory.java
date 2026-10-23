package com.openhire.aiworkers.integrity.client.http;

import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.ApplicationDirectory;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.ApplicationInfo;
import com.openhire.aiworkers.shared.client.http.InternalClient;
import com.openhire.support.Access;
import com.openhire.support.Records;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HttpApplicationDirectory implements ApplicationDirectory {
    private final InternalClient applications;

    HttpApplicationDirectory(Access access, @Value("${openhire.application-url}") String applicationUrl) {
        applications = new InternalClient(applicationUrl, access.secret(), 15000);
    }

    @Override
    public ApplicationInfo application(String tenantId, String applicationId) {
        var row = applications.get(
                "/api/v1/internal/applications/{id}/projection?tenantId={tenant}", applicationId, tenantId);
        if (row == null) throw new IllegalStateException("Application " + applicationId + " was not found");
        return new ApplicationInfo(
                Records.text(row, "candidateId"),
                Records.text(row, "candidateName"),
                Records.text(row, "candidateEmail"),
                Records.text(row, "candidatePhone"),
                Records.text(row, "resumeId"));
    }
}
