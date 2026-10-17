package com.openhire.applicationservice.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class AiAssessmentViewTest {
    private static Document row() {
        return new Document("_id", "app-1")
                .append("stage", "SHORTLISTED")
                .append("decidedBy", "recruiter-1")
                .append(
                        "assessment",
                        new Document("score", 80)
                                .append(
                                        "criteria",
                                        List.of(new Document("id", "r1").append("evidence", List.of("quote")))));
    }

    @Test
    void legacyKeywordAssessmentsReadAsPartialWithTheStandardLabel() {
        var view = AiAssessments.view(row());

        assertThat(view.getString("status")).isEqualTo("PARTIAL");
        assertThat(view.getString("label")).isEqualTo("AI unavailable, partial assessment");
        assertThat(view.getString("decidedBy")).isEqualTo("recruiter-1");
        assertThat(view.getString("decidedAction")).isEqualTo("SHORTLISTED");
        assertThat((List<?>) view.get("integrityFlags")).isEmpty();
        assertThat(view.containsKey("integrityFlag")).isFalse();
    }

    @Test
    void keepsCriteriaEvidenceAsStringQuotesAndAddsIntegrityFlags() {
        var row = row();
        row.put(
                "integrityFlags",
                List.of(new Document("id", "s1")
                        .append("type", "X")
                        .append("severity", "LOW")
                        .append("reason", "why")));

        var view = AiAssessments.view(row);

        var criterion = (Document) ((List<?>) view.get("criteria")).get(0);
        assertThat(criterion.get("evidence")).isEqualTo(List.of("quote"));
        assertThat(((Document) view.get("integrityFlag")).getString("reason")).isEqualTo("why");
        assertThat((List<?>) view.get("integrityFlags")).hasSize(1);
    }
}
