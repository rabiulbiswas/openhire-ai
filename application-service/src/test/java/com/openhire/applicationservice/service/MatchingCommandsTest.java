package com.openhire.applicationservice.service;

import static com.openhire.support.Records.doc;
import static com.openhire.support.Records.documents;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class MatchingCommandsTest {
    private static Document row() {
        return new Document("_id", "app-1")
                .append("candidateId", "cand-1")
                .append("name", "Priya Das")
                .append("resumeId", "res-1")
                .append("jobId", "job-1")
                .append("jobTitle", "Backend Engineer")
                .append("answers", new Document("permit", "yes"))
                .append("eligibilityApproved", true)
                .append(
                        "jobSnapshot",
                        new Document("version", 4)
                                .append("mustHaveSkills", List.of("Java", "Kafka"))
                                .append(
                                        "rubric",
                                        List.of(
                                                new Document("id", "r1")
                                                        .append("label", "Backend depth (Java)")
                                                        .append("weight", 30),
                                                new Document("id", "r2")
                                                        .append("label", "Communication")
                                                        .append("weight", 10)))
                                .append(
                                        "knockoutQuestions",
                                        List.of(new Document("id", "permit")
                                                .append("question", "Work permit?")
                                                .append("requiredAnswer", "yes"))));
    }

    @Test
    void buildsTheContractPayloadWithRubricMustSkillsAndAnswers() {
        var event = MatchingCommands.command("t1", row());

        assertThat(event.getString("eventType")).isEqualTo("matching.requested.v1");
        assertThat(event.getString("tenantId")).isEqualTo("t1");
        assertThat(event.getString("aggregateType")).isEqualTo("Application");
        assertThat(event.getString("aggregateId")).isEqualTo("app-1");
        assertThat(event.getString("eventId")).isNotBlank();
        var payload = doc(event.get("payload"));
        assertThat(payload.getString("candidateName")).isEqualTo("Priya Das");
        assertThat(payload.getString("resumeId")).isEqualTo("res-1");
        assertThat(payload.get("jobVersion")).isEqualTo(4);
        assertThat(payload.get("eligibilityApproved")).isEqualTo(true);
        assertThat(doc(payload.get("answers")).getString("permit")).isEqualTo("yes");
        assertThat(documents(payload.get("knockoutQuestions")).get(0).getString("requiredAnswer"))
                .isEqualTo("yes");
        assertThat(payload.get("mustHaveSkills")).isEqualTo(List.of("Java", "Kafka"));
    }

    @Test
    void mustHaveSkillsMissingFromTheRubricBecomeMustCriteria() {
        var rubric = documents(
                doc(MatchingCommands.command("t1", row()).get("payload")).get("rubric"));

        assertThat(rubric)
                .extracting(c -> c.getString("label"))
                .containsExactly("Backend depth (Java)", "Communication", "Kafka");
        // "Backend depth (Java)" already mentions Java: MUST by must-have match; Communication stays NICE.
        assertThat(rubric).extracting(c -> c.getString("requirement")).containsExactly("MUST", "NICE", "MUST");
        assertThat(rubric.get(2).get("weight")).isEqualTo(20.0);
    }

    @Test
    void anApplicationWithoutRubricUsesJustTheMustHaveSkills() {
        var row = row();
        doc(row.get("jobSnapshot")).remove("rubric");

        var rubric = documents(
                doc(MatchingCommands.command("t1", row).get("payload")).get("rubric"));

        assertThat(rubric).extracting(c -> c.getString("label")).containsExactly("Java", "Kafka");
        assertThat(rubric)
                .allSatisfy(c -> assertThat(c.getString("requirement")).isEqualTo("MUST"));
    }

    @Test
    void screeningMarksTheKeywordAssessmentPartialWithTheStandardLabel() {
        var assessment = SubmissionService.screen(row(), "Java and Kafka");

        assertThat(assessment.getString("status")).isEqualTo("PARTIAL");
        assertThat(assessment.getString("label")).isEqualTo("AI unavailable, partial assessment");
        assertThat(assessment.get("jobVersion")).isEqualTo(4);
    }
}
