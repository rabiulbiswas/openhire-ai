package com.openhire.applicationservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CareerValidationTest {
    @Test
    void validatesEveryQuestionFromAnHttpDecodedJobWithoutCastingNestedMaps() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://job.test/intake"))
                .andRespond(withSuccess(
                        """
                        {"approvedVersion":2,"currentVersion":{"version":2,"knockoutQuestions":[
                          {"id":"java","question":"Java experience?","requiredAnswer":"yes"},
                          {"id":"systems","question":"Systems experience?","requiredAnswer":"yes"}
                        ]}}
                        """,
                        MediaType.APPLICATION_JSON));
        var job = builder.build().get().uri("http://job.test/intake").retrieve().body(Document.class);
        assertThat(job).isNotNull();
        var version = com.openhire.support.Records.doc(job.get("currentVersion"));
        assertThat(((List<?>) version.get("knockoutQuestions")).getFirst())
                .isInstanceOf(java.util.Map.class)
                .isNotInstanceOf(Document.class);
        var input = new Document("answers", java.util.Map.of("java", "yes", "systems", "no"));
        // Different answers are allowed: eligibility is assessed later, not by this validator.
        assertThatCode(() -> SubmissionService.validateCareerJob(input, job)).doesNotThrowAnyException();
        input.put("answers", java.util.Map.of("java", "yes"));
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        input.put("answers", java.util.Map.of("java", "yes", "systems", "   "));
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        server.verify();
    }

    @Test
    void acceptsOnlyTheCurrentApprovedVersionAndCompleteTextAnswers() {
        var version = new Document("version", 2)
                .append("knockoutQuestions", List.of(new Document("id", "q1").append("requiredAnswer", "yes")));
        var job = new Document("approvedVersion", 2).append("currentVersion", version);
        var input = new Document("answers", new Document("q1", "yes"));
        SubmissionService.validateCareerJob(input, job);
        job.put("approvedVersion", 1);
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job))
                .hasMessageContaining("not open");
        job.put("approvedVersion", 2);
        input.put("answers", new Document());
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job))
                .hasMessageContaining("required");
        input.put("answers", new Document("q1", new Document("invalid", "object")));
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job))
                .hasMessageContaining("text");
        input.put("answers", new Document("q1", "a".repeat(2001)));
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job))
                .hasMessageContaining("2000");
    }
}
