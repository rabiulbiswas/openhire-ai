package com.openhire.talent.service;

import static com.openhire.talent.service.Memory.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.talent.domain.TalentTypes.Consent;
import com.openhire.talent.domain.TalentTypes.ProfileState;
import com.openhire.talent.domain.TalentTypes.StructuredResume;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileProjectorTest {
    private static final String RESUME =
            """
            Priya Das
            Senior Backend Engineer, 8 years of experience building Java and Kafka services on Kubernetes.

            Based in Bengaluru. Led a migration to PostgreSQL and microservices.
            """;

    private final Memory memory = new Memory();
    private final ProfileProjector projector = memory.projector();

    @Test
    void aConsentedCandidateBecomesASearchableProfileWithSkillsLocationExperienceAndAVector() {
        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);

        var profile = memory.profileRows.get("t1:cand-1");
        assertThat(profile.consent()).isEqualTo(Consent.GRANTED);
        assertThat(profile.consentUpdatedAt()).isEqualTo(Memory.START);
        assertThat(profile.skills()).contains("java", "kafka", "kubernetes", "postgresql", "microservices");
        assertThat(profile.locations()).containsExactly("Bengaluru");
        assertThat(profile.totalYears()).isEqualTo(8.0);
        assertThat(profile.embedded()).isTrue();
        assertThat(profile.searchable(memory.clock.instant(), false)).isTrue();
        assertThat(memory.vectorRows).containsKey("t1:cand-1");
        assertThat(memory.vectorRows.get("t1:cand-1")).hasSize(64);
        assertThat(profile.retentionUntil()).isEqualTo(java.time.Instant.parse("2028-10-15T00:00:00Z"));
    }

    @Test
    void theModelStructuredResumeEnrichesTheTaxonomyResult() {
        memory.gateway.structured = new StructuredResume(
                "Platform engineer", List.of("Apache Kafka", "Event sourcing"), List.of("Staff Engineer"), 11.0);

        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);

        var profile = memory.profileRows.get("t1:cand-1");
        assertThat(profile.skills()).contains("kafka", "event sourcing", "java");
        assertThat(profile.titles()).containsExactly("Staff Engineer");
        assertThat(profile.totalYears()).isEqualTo(11.0);
        assertThat(profile.displayTitle()).isEqualTo("Staff Engineer");
    }

    @Test
    void withoutConsentOnlyIdentityAndHistoryAreKeptAndNoVectorExists() {
        memory.application("app-1", "cand-1", "Priya Das", "job-1", "Backend Engineer", "APPLIED", "res-1");
        memory.resumeText.put("res-1", RESUME);

        projector.onApplicationEvent(TENANT, "e1", "ApplicationScreened", "app-1", memory.clock.instant(), Map.of());

        var profile = memory.profileRows.get("t1:cand-1");
        assertThat(profile.consent()).isEqualTo(Consent.UNKNOWN);
        assertThat(profile.contentBuilt()).isFalse();
        assertThat(profile.skills()).isEmpty();
        assertThat(memory.vectorRows).isEmpty();
        assertThat(profile.searchable(memory.clock.instant(), false)).isFalse();
    }

    @Test
    void revokingConsentRemovesTheVectorContentAndSearchabilityAtOnce() {
        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);

        projector.applyConsent(TENANT, "app-cand-1", "cand-1", "Priya Das", false);

        var profile = memory.profileRows.get("t1:cand-1");
        assertThat(profile.consent()).isEqualTo(Consent.REVOKED);
        assertThat(profile.skills()).isEmpty();
        assertThat(profile.summary()).isNull();
        assertThat(memory.vectorRows).isEmpty();
        assertThat(memory.removedFromRuns).contains("cand-1");
        assertThat(memory.profiles.searchable(TENANT, memory.clock.instant(), false))
                .isEmpty();
    }

    @Test
    void aProfilePastItsRetentionDateExpiresAndLosesItsVectorButNewActivityRestartsTheClock() {
        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);
        memory.pooled(projector, "cand-2", "Rohan Rao", "job-1", "Backend Engineer", RESUME);
        memory.clock.advance(Duration.ofDays(365));
        // cand-2 applies again a year in, which restarts its retention window.
        memory.application("app-2b", "cand-2", "Rohan Rao", "job-9", "Platform Engineer", "APPLIED", "res-cand-2");
        projector.onApplicationEvent(
                TENANT, "e-new", "ApplicationSubmitted", "app-2b", memory.clock.instant(), Map.of());
        memory.clock.advance(Duration.ofDays(400));

        int expired = projector.sweepRetention();

        assertThat(expired).isEqualTo(1);
        var first = memory.profileRows.get("t1:cand-1");
        assertThat(first.state()).isEqualTo(ProfileState.EXPIRED);
        assertThat(first.skills()).isEmpty();
        assertThat(memory.vectorRows).doesNotContainKey("t1:cand-1").containsKey("t1:cand-2");
        assertThat(memory.profiles.searchable(TENANT, memory.clock.instant(), false))
                .extracting(p -> p.candidateId())
                .containsExactly("cand-2");
    }

    @Test
    void aCurrentEmployeeLeavesThePoolUnlessInternalMobilityIsOn() {
        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);

        projector.onEmployeeCreated(TENANT, "cand-1", "app-cand-1");

        assertThat(memory.profiles.searchable(TENANT, memory.clock.instant(), false))
                .isEmpty();
        assertThat(memory.profiles.searchable(TENANT, memory.clock.instant(), true))
                .hasSize(1);
    }

    @Test
    void anEmployeeEventForAnUnknownCandidateStillExcludesThemWhenTheyLaterConsent() {
        projector.onEmployeeCreated(TENANT, "cand-1", null);
        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);

        assertThat(memory.profiles.searchable(TENANT, memory.clock.instant(), false))
                .isEmpty();
    }

    @Test
    void stageStepsAreRecordedOnceUnderRedeliveryAndASilverMedalistIsRecognised() {
        memory.application("app-1", "cand-1", "Priya Das", "job-1", "Backend Engineer", "INTERVIEWING", "res-1");
        projector.onApplicationEvent(
                TENANT,
                "evt-a",
                "ApplicationStageChanged",
                "app-1",
                memory.clock.instant(),
                Map.of("from", "SHORTLISTED", "to", "INTERVIEWING"));
        projector.onApplicationEvent(
                TENANT,
                "evt-a",
                "ApplicationStageChanged",
                "app-1",
                memory.clock.instant(),
                Map.of("from", "SHORTLISTED", "to", "INTERVIEWING"));
        var profile = memory.profileRows.get("t1:cand-1");
        assertThat(profile.stageHistory()).hasSize(1);
        assertThat(profile.silverMedalist()).isFalse();

        memory.application("app-1", "cand-1", "Priya Das", "job-1", "Backend Engineer", "REJECTED", "res-1");
        projector.onApplicationEvent(
                TENANT,
                "evt-b",
                "ApplicationStageChanged",
                "app-1",
                memory.clock.instant(),
                Map.of("from", "INTERVIEWING", "to", "REJECTED"));

        assertThat(profile.stageHistory()).hasSize(2);
        assertThat(profile.silverMedalist()).isTrue();
        assertThat(profile.reachedInterview()).isTrue();
    }

    @Test
    void whenTheModelIsDownTheTaxonomyFillsInAndTheEmbeddingIsRetriedLater() {
        memory.gateway.down = true;

        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);

        var profile = memory.profileRows.get("t1:cand-1");
        assertThat(profile.contentBuilt()).isTrue();
        assertThat(profile.skills()).contains("java", "kafka");
        assertThat(profile.embedded()).isFalse();
        assertThat(memory.vectorRows).isEmpty();

        memory.gateway.down = false;
        assertThat(projector.retryEmbeddings()).isEqualTo(1);
        assertThat(profile.embedded()).isTrue();
        assertThat(memory.vectorRows).containsKey("t1:cand-1");
    }

    @Test
    void aCompleteAssessmentAddsItsScoreAndEvidenceOnlyForConsentedCandidates() {
        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);

        projector.onAssessment(
                TENANT,
                "app-cand-1",
                "COMPLETE",
                78,
                List.of("Strength noted: Kafka at scale"),
                memory.clock.instant());
        projector.onAssessment(TENANT, "app-cand-1", "PARTIAL", 12, List.of("ignored"), memory.clock.instant());

        var profile = memory.profileRows.get("t1:cand-1");
        assertThat(profile.application("app-cand-1").assessmentScore()).isEqualTo(78);
        assertThat(profile.historical()).extracting(e -> e.text()).containsExactly("Strength noted: Kafka at scale");
    }

    @Test
    void aCandidateMergeMovesApplicationsAndTheStricterConsentWins() {
        memory.pooled(projector, "cand-1", "Priya Das", "job-1", "Backend Engineer", RESUME);
        memory.application("app-2", "cand-2", "Priya D", "job-2", "Platform Engineer", "APPLIED", "res-2");
        projector.onApplicationEvent(TENANT, "e-2", "ApplicationSubmitted", "app-2", memory.clock.instant(), Map.of());
        projector.applyConsent(TENANT, "app-2", "cand-2", "Priya D", false);

        projector.onCandidateMerged(TENANT, "cand-2", "cand-1");

        assertThat(memory.profileRows).doesNotContainKey("t1:cand-2");
        var survivor = memory.profileRows.get("t1:cand-1");
        assertThat(survivor.consent()).isEqualTo(Consent.REVOKED);
        assertThat(survivor.applications()).hasSize(2);
        assertThat(memory.vectorRows).isEmpty();
    }
}
