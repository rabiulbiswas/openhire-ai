package com.openhire.communication.client.http;

import com.openhire.communication.service.port.CommunicationPorts.Contact;
import com.openhire.communication.service.port.CommunicationPorts.ContactDirectory;
import com.openhire.platform.web.ResourceNotFoundException;
import com.openhire.support.Access;
import com.openhire.support.ServiceClient;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/** Candidate contact details stay with application-service; they are fetched per message, not copied into events. */
@Component
class HttpContactDirectory implements ContactDirectory {
    private final ServiceClient applications;

    HttpContactDirectory(Access access, @Value("${openhire.application-url}") String applicationUrl) {
        applications = new ServiceClient(applicationUrl, access.secret());
    }

    @Override
    public Contact contact(String tenantId, String applicationId) {
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
        return new Contact(
                applicationId,
                row.getString("candidateId"),
                row.getString("candidateName"),
                blankToNull(row.getString("candidateEmail")),
                blankToNull(row.getString("candidatePhone")),
                row.getString("jobTitle"));
    }

    @Override
    public Contact verifyReceipt(String tenantId, String applicationId, String receipt) {
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
        return contact(tenantId, applicationId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
