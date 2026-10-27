package com.openhire.applicationservice.service;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class CareerValidationTest {
    @Test
    void acceptsOnlyTheCurrentApprovedVersionAndCompleteTextAnswers() {
        var version = new Document("version", 2).append("knockoutQuestions", List.of(new Document("id", "q1").append("requiredAnswer", "yes")));
        var job = new Document("approvedVersion", 2).append("currentVersion", version);
        var input = new Document("answers", new Document("q1", "yes"));
        SubmissionService.validateCareerJob(input, job);
        job.put("approvedVersion", 1);
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job)).hasMessageContaining("not open");
        job.put("approvedVersion", 2);
        input.put("answers", new Document());
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job)).hasMessageContaining("required");
        input.put("answers", new Document("q1", new Document("invalid", "object")));
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job)).hasMessageContaining("text");
        input.put("answers", new Document("q1", "a".repeat(2001)));
        assertThatThrownBy(() -> SubmissionService.validateCareerJob(input, job)).hasMessageContaining("2000");
    }
}
