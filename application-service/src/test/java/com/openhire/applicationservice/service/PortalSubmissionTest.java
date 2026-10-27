package com.openhire.applicationservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.service.port.ConsentSource;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.Access;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PortalSubmissionTest {
    @Test
    void attributesTrustedImportsDeduplicatesPerProviderAndJobAndPreservesConsent() throws Exception {
        var store = new FakeSubmissions();
        when(store.mongo.insert(any(Document.class), eq("submissions"))).thenAnswer(c -> store.put(c.getArgument(0)));
        ConsentSource none = tenant -> java.util.Optional.empty();
        var service =
                new SubmissionService(
                        store.mongo,
                        new Access("local", "t", "secret"),
                        new ObjectMapper(),
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1",
                        new ConsentTexts(none, "privacy-v1", "t"),
                        mock(AuditRecorder.class)) {
                    @Override
                    public String consentVersion(String tenant) {
                        return "privacy-v1";
                    }

                    @Override
                    public Document job(String tenant, String id) {
                        return new Document("status", "OPEN").append("title", "Engineer");
                    }
                };
        var input = new Document("jobId", "job-a")
                .append("name", "Ada")
                .append("email", "ada@example.test")
                .append("processingConsent", true)
                .append("consentVersion", "privacy-v1")
                .append("externalReference", "vendor-1");
        var first = service.submitPortal("t", "LINKEDIN", "delivery-1", input, null);
        assertThat(first).containsEntry("source", "PUBLIC_API").containsEntry("sourceProvider", "LINKEDIN");
        assertThat(service.submitPortal("t", "LINKEDIN", "delivery-2", input, null)
                        .getString("_id"))
                .isEqualTo(first.getString("_id"));
        assertThat(service.submitPortal("t", "NAUKRI", "delivery-1", input, null)
                        .getString("_id"))
                .isNotEqualTo(first.getString("_id"));
        input.put("jobId", "job-b");
        assertThat(service.submitPortal("t", "LINKEDIN", "delivery-1", input, null)
                        .getString("_id"))
                .isNotEqualTo(first.getString("_id"));
        input.put("processingConsent", false);
        assertThatThrownBy(() -> service.submitPortal("t", "LINKEDIN", "delivery-3", input, null))
                .hasMessageContaining("Processing consent");
        input.put("processingConsent", true);
        input.put("sourceProvider", "LINKEDIN");
        var ordinary = service.submit("t", "recruiter", "ordinary", input, null);
        assertThat(ordinary.get("sourceProvider")).isNull();
        assertThatThrownBy(() -> service.submitPortal("t", "UNKNOWN", "x", input, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
