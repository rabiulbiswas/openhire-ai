package com.openhire.candidateservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import com.openhire.platform.audit.AuditRecorder;

class CandidateProfileTest {
    private final InMemoryMongo store = new InMemoryMongo();
    private final CandidateService service = new CandidateService(store.template, mock(AuditRecorder.class));

    private Document candidate() {
        var row = new Document("_id", "candidate").append("tenantId", "tenant-a").append("name", "Ada")
                .append("title", "").append("location", "");
        store.collection("candidates").add(row);
        return row;
    }

    @Test
    void editedProfileIsReturnedInTheDirectory() {
        candidate();
        service.profile("tenant-a", "candidate", "recruiter", new Document("title", " Backend Engineer ").append("location", " Kolkata "));
        var result = (java.util.List<?>) service.list("tenant-a", null, null, 25).get("data");
        assertThat((Document) result.getFirst()).containsEntry("title", "Backend Engineer").containsEntry("location", "Kolkata");
    }

    @Test
    void cannotEditAnotherTenantOrRestoreAnErasedProfile() {
        var row = candidate();
        var input = new Document("title", "Backend Engineer").append("location", "Kolkata");
        assertThatThrownBy(() -> service.profile("tenant-b", "candidate", "recruiter", input))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        row.put("erased", true);
        assertThatThrownBy(() -> service.profile("tenant-a", "candidate", "recruiter", input))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(row.getString("title")).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsAndOversizedValues() {
        candidate();
        assertThatThrownBy(() -> service.profile("tenant-a", "candidate", "recruiter", new Document("tenantId", "other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.profile("tenant-a", "candidate", "recruiter", new Document("title", "x".repeat(201)).append("location", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
