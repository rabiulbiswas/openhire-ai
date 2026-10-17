package com.openhire.employee.adapter.out.http;

import com.openhire.employee.application.port.EmployeePorts.ApplicationDirectory;
import com.openhire.employee.application.port.EmployeePorts.ApplicationSnapshot;
import com.openhire.platform.web.ResourceNotFoundException;
import com.openhire.support.Access;
import com.openhire.support.Records;
import com.openhire.support.ServiceClient;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Component
class HttpApplicationDirectory implements ApplicationDirectory {
    private final ServiceClient applications;

    HttpApplicationDirectory(Access access, @Value("${openhire.application-url}") String applicationUrl) {
        applications = new ServiceClient(applicationUrl, access.secret());
    }

    @Override
    public ApplicationSnapshot application(String tenantId, String applicationId) {
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
        return new ApplicationSnapshot(
                Records.text(row, "candidateName"),
                Records.text(row, "candidateEmail"),
                Records.text(row, "candidatePhone"),
                Records.text(row, "source"),
                Records.text(row, "actorId"),
                Records.text(row, "stage"));
    }
}
