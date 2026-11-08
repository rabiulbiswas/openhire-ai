package com.openhire.candidateservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.platform.audit.AuditRecorder;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class CandidateProfileTest {
    private final InMemoryMongo store = new InMemoryMongo();
    private final CandidateService service = new CandidateService(store.template, mock(AuditRecorder.class));

    private Document candidate() {
        var row = new Document("_id", "candidate")
                .append("tenantId", "tenant-a")
                .append("name", "Ada")
                .append("title", "")
                .append("location", "");
        store.collection("candidates").add(row);
        return row;
    }

    @Test
    void editedProfileIsReturnedInTheDirectory() {
        candidate();
        service.profile(
                "tenant-a",
                "candidate",
                "recruiter",
                new Document("title", " Backend Engineer ").append("location", " Kolkata "));
        var result =
                (java.util.List<?>) service.list("tenant-a", null, null, 25).get("data");
        assertThat((Document) result.getFirst())
                .containsEntry("title", "Backend Engineer")
                .containsEntry("location", "Kolkata");
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
        assertThatThrownBy(
                        () -> service.profile("tenant-a", "candidate", "recruiter", new Document("tenantId", "other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.profile(
                        "tenant-a",
                        "candidate",
                        "recruiter",
                        new Document("title", "x".repeat(201)).append("location", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void richProfileEditsNormalizeValuesAndPreserveUnchangedProvenance() {
        var row = candidate();
        row.put("provenance", new Document("title", new Document("source", "RESUME")));
        service.profile(
                "tenant-a",
                "candidate",
                "recruiter",
                new Document("title", "")
                        .append("location", "")
                        .append("employer", " Example ")
                        .append("skills", java.util.List.of(" Java ", "java", "SQL"))
                        .append(
                                "experience",
                                java.util.List.of(new Document("company", " Example ")
                                        .append("title", "Engineer")
                                        .append("period", "2022 to present"))));
        assertThat(row.getList("skills", Document.class))
                .extracting(d -> d.getString("name"))
                .containsExactly("Java", "SQL");
        assertThat(((Document) row.get("provenance")).get("title")).isEqualTo(new Document("source", "RESUME"));
        assertThat(((Document) ((Document) row.get("provenance")).get("skills")))
                .containsEntry("source", "RECRUITER_PROFILE_EDIT");
    }

    @Test
    void explicitlyClearedDerivedSkillsRecordManualOverrideEvenWhenStorageWasEmpty() {
        var row = candidate().append("skills", java.util.List.of());
        service.profile(
                "tenant-a",
                "candidate",
                "recruiter",
                new Document("title", "").append("location", "").append("skills", java.util.List.of()));
        assertThat(((Document) ((Document) row.get("provenance")).get("skills")))
                .containsEntry("source", "RECRUITER_PROFILE_EDIT");
    }

    @Test
    void rejectsSkillSourceInjectionAndInvalidExperience() {
        candidate();
        for (var input : java.util.List.of(
                new Document("skills", java.util.List.of(new Document("name", "Java").append("source", "RESUME"))),
                new Document("skills", java.util.List.of("x".repeat(121))),
                new Document("experience", java.util.List.of(new Document("period", "2022"))),
                new Document(
                        "experience",
                        java.util.List.of(new Document("company", "Example").append("source", "RESUME"))))) {
            input.append("title", "").append("location", "");
            assertThatThrownBy(() -> service.profile("tenant-a", "candidate", "recruiter", input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
