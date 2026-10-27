package com.openhire.applicationservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.service.port.ConsentSource;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.Access;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SubmissionProfileTest {
    @Test
    void keepsCandidateProfileFieldsAndIncludesThemInIdempotencyChecks() throws Exception {
        var store = new FakeSubmissions();
        when(store.mongo.insert(any(Document.class), eq("submissions")))
                .thenAnswer(call -> store.put(call.getArgument(0)));
        ConsentSource source = tenant -> java.util.Optional.empty();
        var service =
                new SubmissionService(
                        store.mongo,
                        new Access("local", "tenant-a", "secret"),
                        new ObjectMapper(),
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1",
                        new ConsentTexts(source, "privacy-v1", "tenant-a"),
                        mock(AuditRecorder.class)) {
                    @Override
                    public String consentVersion(String tenant) {
                        return "privacy-v1";
                    }

                    @Override
                    public Document job(String tenant, String id) {
                        return new Document("status", "OPEN").append("title", "Different vacancy");
                    }
                };
        var input = new Document("jobId", "job-a")
                .append("name", "Ada")
                .append("email", "ada@example.test")
                .append("source", "RECRUITER")
                .append("processingConsent", true)
                .append("consentVersion", "privacy-v1")
                .append("title", " Backend Engineer ")
                .append("phone", "+91 99000 12345")
                .append("noticePeriod", "30")
                .append("candidateReason", " Strong Java experience ")
                .append("location", " Kolkata ");
        var row = service.submit("tenant-a", "recruiter", "profile-key", input, null);
        assertThat(row)
                .containsEntry("title", "Backend Engineer")
                .containsEntry("location", "Kolkata")
                .containsEntry("noticePeriod", "30")
                .containsEntry("candidateReason", "Strong Java experience")
                .containsEntry("jobTitle", "Different vacancy");
        service.submit("tenant-a", "recruiter", "profile-key", input, null);
        input.put("location", "Pune");
        assertThatThrownBy(() -> service.submit("tenant-a", "recruiter", "profile-key", input, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        input.put("location", " Kolkata ");
        input.put("noticePeriod", "invalid");
        assertThatThrownBy(() -> service.submit("tenant-a", "recruiter", "profile-key", input, null))
                .isInstanceOf(IllegalArgumentException.class);
        input.put("noticePeriod", "30");
        input.put("phone", "bad");
        assertThatThrownBy(() -> service.submit("tenant-a", "recruiter", "profile-key", input, null))
                .isInstanceOf(IllegalArgumentException.class);
        input.put("phone", "");
        input.put("candidateReason", "a".repeat(2001));
        assertThatThrownBy(() -> service.submit("tenant-a", "recruiter", "profile-key", input, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
