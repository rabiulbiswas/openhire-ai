package com.openhire.talent.application;

import static com.openhire.talent.application.Memory.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.talent.application.port.TalentPorts.JobInfo;
import com.openhire.talent.domain.Invitation;
import com.openhire.talent.domain.TalentTypes.Chunk;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RediscoveryServiceTest {
    private static final String JAVA_KAFKA =
            "Senior engineer with 7 years of experience in Java, Kafka and microservices.\n\nBased in Pune.";
    private static final String PYTHON = "Data analyst with 3 years of experience in Python, Tableau and Excel.";

    private final Memory memory = new Memory();
    private final ProfileProjector projector = memory.projector();
    private final RediscoveryService rediscovery = memory.rediscovery();

    @BeforeEach
    void job() {
        memory.jobRows.put(
                "job-new",
                new JobInfo(
                        "job-new",
                        "Backend Engineer",
                        "OPEN",
                        "Build Java and Kafka services for payments.",
                        List.of("Java", "Kafka"),
                        List.of("Java", "Kafka", "System design")));
    }

    private void interviewedThenRejected(String candidateId) {
        var app = "app-" + candidateId;
        memory.application(
                app, candidateId, candidateId, "job-0", "Backend Engineer", "REJECTED", "res-" + candidateId);
        projector.onApplicationEvent(
                TENANT,
                "i-" + candidateId,
                "ApplicationStageChanged",
                app,
                memory.clock.instant(),
                Map.of("from", "SHORTLISTED", "to", "INTERVIEWING"));
        projector.onApplicationEvent(
                TENANT,
                "r-" + candidateId,
                "ApplicationStageChanged",
                app,
                memory.clock.instant(),
                Map.of("from", "INTERVIEWING", "to", "REJECTED"));
    }

    @Test
    void theFilterKeepsOnlyConsentedUnexpiredNonEmployeeCandidatesWhoHaveNotAppliedOrBeenInvitedRecently() {
        memory.pooled(projector, "good", "Gita", "job-0", "Backend Engineer", JAVA_KAFKA);
        memory.pooled(projector, "other", "Omar", "job-0", "Analyst", PYTHON);
        memory.pooled(projector, "revoked", "Rev", "job-0", "Backend Engineer", JAVA_KAFKA);
        projector.applyConsent(TENANT, "app-revoked", "revoked", "Rev", false);
        memory.pooled(projector, "applied", "App", "job-new", "Backend Engineer", JAVA_KAFKA);
        memory.pooled(projector, "cooling", "Coo", "job-0", "Backend Engineer", JAVA_KAFKA);
        memory.invitationRows.add(new Invitation(
                "i1",
                TENANT,
                "cooling",
                null,
                null,
                "app-cooling",
                "u",
                memory.clock.instant().minus(Duration.ofDays(5))));
        memory.pooled(projector, "staff", "Emp", "job-0", "Backend Engineer", JAVA_KAFKA);
        projector.onEmployeeCreated(TENANT, "staff", "app-staff");
        memory.pooled(projector, "old", "Old", "job-0", "Backend Engineer", JAVA_KAFKA);
        memory.clock.advance(Duration.ofDays(800));
        memory.pooled(projector, "recent", "Rec", "job-0", "Backend Engineer", JAVA_KAFKA);
        projector.sweepRetention();

        var result = rediscovery.forJob(TENANT, "job-new", false);

        assertThat(result.candidates()).extracting(c -> c.id()).containsExactlyInAnyOrder("recent");
        assertThat(result.rediscoveryForJobTitle()).isEqualTo("Backend Engineer");
        assertThat(result.filters()).contains("Role: Backend Engineer", "Skill: Java", "Skill: Kafka");
    }

    @Test
    void candidatesAreRankedByTheWeightedScoreAndTheSilverMedalistGetsItsBoost() {
        memory.pooled(projector, "plain", "Plain", "job-0", "Backend Engineer", JAVA_KAFKA);
        memory.pooled(projector, "silver", "Silver", "job-0", "Backend Engineer", JAVA_KAFKA);
        interviewedThenRejected("silver");
        memory.pooled(projector, "weak", "Weak", "job-0", "Analyst", PYTHON);

        var result = rediscovery.forJob(TENANT, "job-new", false);

        assertThat(result.candidates()).extracting(c -> c.id()).containsExactly("silver", "plain", "weak");
        assertThat(result.candidates().getFirst().matchScore())
                .isGreaterThan(result.candidates().get(1).matchScore());
        assertThat(result.semanticMode()).isEqualTo("EMBEDDING");
    }

    @Test
    void explanationsKeepHistoricalAndCurrentEvidenceSeparateAndOnlyTheTopFewAreModelWritten() {
        memory.pooled(projector, "a", "A", "job-0", "Backend Engineer", JAVA_KAFKA);
        interviewedThenRejected("a");
        memory.pooled(projector, "b", "B", "job-0", "Backend Engineer", JAVA_KAFKA);
        memory.pooled(projector, "c", "C", "job-0", "Analyst", PYTHON);

        var result = rediscovery.forJob(TENANT, "job-new", false);

        assertThat(result.candidates()).extracting(c -> c.explained()).containsExactly(true, true, false);
        var request = memory.gateway.explainRequests.getFirst();
        assertThat(request.jobTitle()).isEqualTo("Backend Engineer");
        assertThat(request.criteria()).contains("Java", "System design");
        assertThat(request.historical()).isNotEmpty().allSatisfy(c -> assertThat(c.chunkId())
                .matches("(history|assessment)-\\d+"));
        assertThat(request.historical()).anySatisfy(c -> assertThat(c.text()).contains("Applied for Backend Engineer"));
        assertThat(request.current()).isNotEmpty().allSatisfy(c -> assertThat(c.chunkId())
                .matches("c\\d+"));
        var currentTexts = request.current().stream().map(Chunk::text).toList();
        assertThat(request.historical()).noneMatch(h -> currentTexts.contains(h.text()));
        var top = result.candidates().getFirst();
        assertThat(top.whyFitNow()).startsWith("CURRENT:");
        assertThat(top.historicalEvidence()).startsWith("HISTORICAL:");
        var templated = result.candidates().getLast();
        assertThat(templated.whyFitNow()).startsWith("CURRENT:").contains("Rule-based summary");
        assertThat(templated.historicalEvidence()).startsWith("HISTORICAL:");
    }

    @Test
    void whenTheGatewayIsDownResultsStillComeBackWithTemplateExplanationsFlaggedNotExplained() {
        memory.pooled(projector, "a", "A", "job-0", "Backend Engineer", JAVA_KAFKA);
        memory.pooled(projector, "b", "B", "job-0", "Analyst", PYTHON);
        memory.gateway.down = true;

        var result = rediscovery.forJob(TENANT, "job-new", true);

        assertThat(result.semanticMode()).isEqualTo("LEXICAL");
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().getFirst().id()).isEqualTo("a");
        assertThat(result.candidates()).allSatisfy(c -> {
            assertThat(c.explained()).isFalse();
            assertThat(c.whyFitNow()).contains("Rule-based summary");
        });
        assertThat(memory.gateway.explainRequests).isEmpty();
    }

    @Test
    void anExplanationFailureMidwayStopsModelCallsButKeepsEveryResult() {
        memory.pooled(projector, "a", "A", "job-0", "Backend Engineer", JAVA_KAFKA);
        memory.pooled(projector, "b", "B", "job-0", "Backend Engineer", JAVA_KAFKA);
        memory.gateway.explainDown = true;

        var result = rediscovery.forJob(TENANT, "job-new", false);

        assertThat(result.candidates()).hasSize(2).allSatisfy(c -> assertThat(c.explained())
                .isFalse());
    }

    @Test
    void aRunIsStoredAnEventIsEmittedAndAReloadReusesTheRunWithoutNewModelCalls() {
        memory.pooled(projector, "a", "A", "job-0", "Backend Engineer", JAVA_KAFKA);

        rediscovery.forJob(TENANT, "job-new", false);
        int explains = memory.gateway.explainRequests.size();
        rediscovery.forJob(TENANT, "job-new", false);

        assertThat(memory.runRows).hasSize(1);
        assertThat(memory.gateway.explainRequests).hasSize(explains);
        assertThat(memory.events).singleElement().satisfies(event -> {
            assertThat(event.get("type")).isEqualTo("talent.rediscovery.completed.v1");
            assertThat(event.get("payload")).isEqualTo(Map.of("jobId", "job-new", "matches", 1));
        });
        memory.clock.advance(Duration.ofMinutes(11));
        rediscovery.forJob(TENANT, "job-new", false);
        assertThat(memory.runRows).hasSize(2);
    }

    @Test
    void withoutAJobIdTheMostRecentRunsJobIsUsed() {
        memory.pooled(projector, "a", "A", "job-0", "Backend Engineer", JAVA_KAFKA);
        assertThat(rediscovery.forJob(TENANT, null, false).candidates()).isEmpty();
        rediscovery.onJobPublished(TENANT, "job-new", "evt-1");

        var result = rediscovery.forJob(TENANT, null, false);

        assertThat(result.rediscoveryForJobTitle()).isEqualTo("Backend Engineer");
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void aRedeliveredJobPublishedEventDoesNotRunTwice() {
        rediscovery.onJobPublished(TENANT, "job-new", "evt-1");
        rediscovery.onJobPublished(TENANT, "job-new", "evt-1");

        assertThat(memory.runRows).hasSize(1);
        assertThat(memory.runRows.getFirst().trigger()).isEqualTo("JOB_PUBLISHED");
    }

    @Test
    void revokingConsentErasesTheCandidateFromStoredRuns() {
        memory.pooled(projector, "a", "A", "job-0", "Backend Engineer", JAVA_KAFKA);
        rediscovery.forJob(TENANT, "job-new", false);

        projector.applyConsent(TENANT, "app-a", "a", "A", false);

        assertThat(rediscovery.forJob(TENANT, "job-new", false).candidates()).isEmpty();
    }
}
