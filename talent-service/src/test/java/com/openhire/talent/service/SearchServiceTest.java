package com.openhire.talent.service;

import static com.openhire.talent.service.Memory.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.talent.domain.Invitation;
import com.openhire.talent.domain.TalentTypes.Filters;
import com.openhire.talent.service.port.TalentPorts.NlFilter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchServiceTest {
    private final Memory memory = new Memory();
    private final ProfileProjector projector = memory.projector();
    private final SearchService search = memory.search();

    @BeforeEach
    void pool() {
        memory.pooled(
                projector,
                "kafka-pune",
                "Gita",
                "job-0",
                "Backend Engineer",
                "Engineer with 7 years of experience in Java and Kafka. Based in Pune.");
        memory.pooled(
                projector,
                "kafka-delhi",
                "Dev",
                "job-0",
                "Backend Engineer",
                "Engineer with 3 years of experience in Java and Kafka. Based in Delhi.");
        memory.pooled(
                projector,
                "python-pune",
                "Pia",
                "job-0",
                "Analyst",
                "Analyst with 5 years of experience in Python and Tableau. Based in Pune.");
    }

    @Test
    void aNaturalLanguageQueryBecomesEditableChipsAndAFilteredRankedList() {
        memory.gateway.nl = new NlFilter(
                new Filters(List.of("Pune"), List.of("Kafka"), 5, null, null, null),
                "kafka engineer",
                List.of("Location: Pune", "Skill: Kafka", "5+ years"));

        var result = search.naturalLanguage(TENANT, "Kafka people in Pune with 5 years");

        assertThat(result.filters()).containsExactly("Location: Pune", "Skill: Kafka", "5+ years");
        assertThat(result.filterSpec().skills()).containsExactly("Kafka");
        assertThat(result.semanticQuery()).isEqualTo("kafka engineer");
        assertThat(result.candidates()).extracting(c -> c.id()).containsExactly("kafka-pune");
        assertThat(result.candidates().getFirst().explained()).isFalse();
    }

    @Test
    void anEditedFilterRerunsAndTheChipsAreDerivedFromIt() {
        var result = search.search(
                TENANT, new Filters(List.of(), List.of("kafka"), null, null, null, null), "java engineer");

        assertThat(result.filters()).containsExactly("Skill: kafka");
        assertThat(result.candidates()).extracting(c -> c.id()).containsExactlyInAnyOrder("kafka-pune", "kafka-delhi");
        assertThat(result.candidates())
                .allSatisfy(c -> assertThat(c.matchScore()).isBetween(1, 100));
    }

    @Test
    void whenTheModelIsDownTheSentenceIsSearchedByMeaningWithATextChip() {
        memory.gateway.down = true;

        var result = search.naturalLanguage(TENANT, "tableau analyst");

        assertThat(result.filters()).containsExactly("Text search: tableau analyst");
        assertThat(result.candidates().getFirst().id()).isEqualTo("python-pune");
        assertThat(result.semanticMode()).isEqualTo("LEXICAL");
    }

    @Test
    void unknownValuesNeverSatisfyAFilter() {
        var result = search.search(TENANT, new Filters(List.of(), List.of(), null, null, true, null), null);

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void theInterviewedWithinFilterUsesStageHistory() {
        memory.application(
                "app-kafka-pune", "kafka-pune", "Gita", "job-0", "Backend Engineer", "REJECTED", "res-kafka-pune");
        projector.onApplicationEvent(
                TENANT,
                "ev1",
                "ApplicationStageChanged",
                "app-kafka-pune",
                memory.clock.instant().minus(Duration.ofDays(10)),
                Map.of("from", "SHORTLISTED", "to", "INTERVIEWING"));

        var result = search.search(TENANT, new Filters(List.of(), List.of(), null, 30, null, null), "");

        assertThat(result.candidates()).extracting(c -> c.id()).containsExactly("kafka-pune");
    }

    @Test
    void aBlankNaturalLanguageQueryIsRejected() {
        assertThatThrownBy(() -> search.naturalLanguage(TENANT, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void poolHealthIsComputedFromTheRealPoolAndInvitationLog() {
        // silver medalist
        memory.application(
                "app-kafka-pune", "kafka-pune", "Gita", "job-0", "Backend Engineer", "REJECTED", "res-kafka-pune");
        projector.onApplicationEvent(
                TENANT,
                "ev1",
                "ApplicationStageChanged",
                "app-kafka-pune",
                memory.clock.instant(),
                Map.of("from", "SHORTLISTED", "to", "INTERVIEWING"));
        // consent expiring within 60 days: a candidate whose last activity was 23 months ago
        memory.clock.advance(Duration.ofDays(700));
        memory.pooled(projector, "late", "Late", "job-0", "Backend Engineer", "Java engineer.");
        // re-engaged: invited, then applied and hired
        memory.invitationRows.add(new Invitation(
                "i1",
                TENANT,
                "late",
                null,
                null,
                "app-late",
                "u",
                memory.clock.instant().minus(Duration.ofDays(2))));
        memory.clock.advance(Duration.ofDays(1));
        memory.application("app-late-2", "late", "Late", "job-7", "Platform Engineer", "HIRED", "res-late");
        projector.onApplicationEvent(
                TENANT, "ev2", "ApplicationSubmitted", "app-late-2", memory.clock.instant(), Map.of());

        var health = search.poolHealth(TENANT);

        // the first three candidates are 701 days old (retention 730): they expire within 60 days
        assertThat(health.consentExpiringSoon()).isEqualTo(3);
        assertThat(health.silverMedalists()).isEqualTo(1);
        assertThat(health.reEngagedThisQuarter()).isEqualTo(1);
        assertThat(health.hireRateFromPoolPct()).isEqualTo(100.0);
    }
}
