package com.openhire.aiworkers.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.aiworkers.matching.domain.MatchingRequest.Criterion;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexicalTest {
    private final Lexical lexical = new Lexical(Taxonomy.builtIn());

    private static Criterion criterion(String label) {
        return new Criterion("c", label, "MUST", 1, "");
    }

    @Test
    void taxonomyKnowsSynonymGroups() {
        var taxonomy = Taxonomy.builtIn();
        assertThat(taxonomy.variants("Spring Boot")).contains("spring", "springboot");
        assertThat(taxonomy.variants("k8s")).contains("kubernetes");
        assertThat(taxonomy.variants("Kafka")).contains("event streaming");
        assertThat(taxonomy.variants("cobol")).containsExactly("cobol");
    }

    @Test
    void synonymsCountAsCoverage() {
        assertThat(lexical.coverage(criterion("Kubernetes"), List.of("Kubernetes"), "Ran k8s clusters in production"))
                .isEqualTo(1.0);
        assertThat(lexical.coverage(criterion("Kafka"), List.of("Kafka"), "Designed event streaming platforms"))
                .isEqualTo(1.0);
        assertThat(lexical.coverage(criterion("Spring Boot"), List.of(), "Wrote services with Spring"))
                .isEqualTo(1.0);
    }

    @Test
    void wholeWordsOnlySoJavaIsNotJavaScript() {
        assertThat(lexical.coverage(criterion("Java"), List.of("Java"), "JavaScript engineer"))
                .isZero();
    }

    @Test
    void mustHaveSkillsInsideALabelAreAveraged() {
        var label = criterion("Backend depth (Java, Kafka)");
        assertThat(lexical.coverage(label, List.of("Java", "Kafka"), "Java services"))
                .isEqualTo(0.5);
    }

    @Test
    void multiWordLabelsFallBackToTokenCoverage() {
        var label = criterion("Distributed systems design");
        assertThat(lexical.coverage(label, List.of(), "I design large systems")).isBetween(0.3, 0.7);
        assertThat(lexical.coverage(label, List.of(), "Painting")).isZero();
    }
}
