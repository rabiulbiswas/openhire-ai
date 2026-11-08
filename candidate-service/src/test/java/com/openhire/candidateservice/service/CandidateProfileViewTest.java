package com.openhire.candidateservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.support.ServiceClient;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class CandidateProfileViewTest {
    private final CandidateService candidates = mock(CandidateService.class);
    private final ServiceClient resumes = mock(ServiceClient.class);
    private final CandidateProfileView view = new CandidateProfileView(candidates, resumes);

    private Document candidate() {
        return new Document("email", "ada@example.test")
                .append("name", "Ada Example")
                .append("skills", List.of())
                .append("experience", List.of())
                .append("documents", List.of(new Document("id", "resume")))
                .append("applications", List.of(new Document("applicationId", "app")));
    }

    private Document resume() {
        return new Document("id", "resume")
                .append("tenantId", "tenant")
                .append("applicationId", "app")
                .append("status", "DONE")
                .append("name", "ada.pdf")
                .append("updatedAt", "2026-10-27")
                .append(
                        "text",
                        "Ada Example\nada@example.test\nCORE TECHNICAL SKILLS\nLanguages: Java, SQL, java\nPROFESSIONAL EXPERIENCE\nEngineer — Example Ltd\n2022 to present | Kolkata\nEDUCATION\nComputer Science");
    }

    private void prepare(Document row, Document resume) {
        when(candidates.view("tenant", "candidate")).thenAnswer(invocation -> new Document(row));
        when(resumes.get("/api/v1/internal/resumes/{id}?tenantId={tenant}", "resume", "tenant"))
                .thenReturn(resume);
    }

    @Test
    void matchingLinkedResumeSuppliesMissingEvidenceWithProvenanceWithoutPersisting() {
        var row = candidate();
        prepare(row, resume());
        var result = view.view("tenant", "candidate");
        assertThat(result.getList("skills", Document.class))
                .extracting(d -> d.getString("name"))
                .containsExactly("Java", "SQL");
        assertThat(result.getList("experience", Document.class))
                .containsExactly(new Document("company", "Example Ltd")
                        .append("title", "Engineer")
                        .append("period", "2022 to present"));
        assertThat(((Document) result.get("provenance")).get("skills"))
                .isEqualTo(new Document("source", "RESUME")
                        .append("resumeId", "resume")
                        .append("resumeName", "ada.pdf")
                        .append("recordedAt", "2026-10-27")
                        .append("applicationId", "app"));
        assertThat(row.getList("skills", Document.class)).isEmpty();
        verify(candidates, times(2)).view("tenant", "candidate");
        verifyNoMoreInteractions(candidates);
    }

    @Test
    void linkedResumeSuppliesSkillsAndExperienceWithoutMatchingNameOrEmail() {
        prepare(
                candidate(),
                resume().append("text", "Someone Else\nother@example.test\nSkills\nJava\nExperience\nEngineer"));
        var result = view.view("tenant", "candidate");
        assertThat(result).containsEntry("profileResumeStatus", "AVAILABLE").containsEntry("email", "ada@example.test");
        assertThat(result.getList("skills", Document.class))
                .extracting(d -> d.getString("name"))
                .containsExactly("Java");
        assertThat(result.getList("experience", Document.class)).hasSize(1);
        assertThat(result).containsEntry("name", "Ada Example");
    }

    @Test
    void foreignTenantOrApplicationCannotSupplyProfileEvidence() {
        for (String field : List.of("tenantId", "applicationId", "id")) {
            prepare(candidate(), resume().append(field, "foreign"));
            assertThat(view.view("tenant", "candidate"))
                    .containsEntry("profileResumeStatus", "UNAVAILABLE")
                    .containsEntry("skills", List.of());
        }
    }

    @Test
    void manualClearingAndErasureDoNotReimportResumeData() {
        var row = candidate()
                .append(
                        "provenance",
                        new Document("skills", new Document("source", "RECRUITER_PROFILE_EDIT"))
                                .append("experience", new Document("source", "CANDIDATE_EDIT")));
        prepare(row, resume());
        assertThat(view.view("tenant", "candidate")).containsEntry("skills", List.of());
        verifyNoInteractions(resumes);
        prepare(candidate().append("erased", true), resume());
        assertThat(view.view("tenant", "candidate")).containsEntry("erased", true);
        verifyNoInteractions(resumes);
    }

    @Test
    void concurrentManualEditWinsAndErasureIsRespected() {
        prepare(candidate(), resume());
        var edited =
                candidate().append("skills", List.of(new Document("name", "Python").append("source", "RECRUITER")));
        when(candidates.view("tenant", "candidate")).thenReturn(candidate(), edited);
        assertThat(view.view("tenant", "candidate").getList("skills", Document.class))
                .extracting(d -> d.getString("name"))
                .containsExactly("Python");
        when(candidates.view("tenant", "candidate"))
                .thenReturn(candidate(), candidate().append("erased", true));
        assertThat(view.view("tenant", "candidate"))
                .containsEntry("erased", true)
                .containsEntry("skills", List.of());
    }

    @Test
    void dependencyFailureAndPendingProcessingKeepBasicProfileAvailable() {
        prepare(candidate(), resume().append("status", "PROCESSING"));
        assertThat(view.view("tenant", "candidate")).containsEntry("profileResumeStatus", "PROCESSING");
        when(resumes.get("/api/v1/internal/resumes/{id}?tenantId={tenant}", "resume", "tenant"))
                .thenThrow(new RestClientException("unavailable"));
        assertThat(view.view("tenant", "candidate"))
                .containsEntry("profileResumeStatus", "UNAVAILABLE")
                .containsEntry("email", "ada@example.test");
    }

    @Test
    void unstructuredExperienceIsShownVerbatimAndOtherSectionsAreNotSkills() {
        var result = ResumeProfileEvidence.extract(
                new Document(
                        "sections",
                        new Document(
                                "summary",
                                "A developer with Java experience\nWORK HISTORY\nBuilt systems for Example Ltd\nEDUCATION\nSQL course")));
        assertThat(result.getList("skills", Document.class)).isEmpty();
        assertThat(result.getList("experience", Document.class))
                .containsExactly(new Document("company", "")
                        .append("title", "")
                        .append("period", "")
                        .append("summary", "Built systems for Example Ltd"));
    }
}
