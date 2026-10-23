package com.openhire.candidateservice.messaging.consumer;

import static com.openhire.support.Records.*;

import com.openhire.candidateservice.service.CandidateService;
import com.openhire.support.Access;
import com.openhire.support.ServiceClient;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ApplicationProjectionConsumer {
    private final CandidateService candidates;
    private final ServiceClient applications;

    public ApplicationProjectionConsumer(
            CandidateService candidates,
            Access access,
            @Value("${openhire.application-url:http://localhost:8084}") String url) {
        this.candidates = candidates;
        applications = new ServiceClient(url, access.secret());
    }

    @KafkaListener(topics = "application.events.v1", groupId = "candidate-application-projection-v1")
    public void consume(String message) {
        var event = Document.parse(message);
        if (!java.util.Set.of(
                        "ApplicationScreened",
                        "ApplicationStageChanged",
                        "ApplicationDecisionUndone",
                        "ApplicationNeedsAttention",
                        "AssessmentOverridden",
                        "ApplicationEligibilityReviewed")
                .contains(text(event, "eventType"))) return;
        // Legacy intake uses the same topic but owns a separate collection.
        if (!"phase3-workflow".equals(text(event, "producer"))) return;
        String id = required(event, "aggregateId", 100);
        var projection = applications.get(
                "/api/v1/internal/applications/{id}/projection?tenantId={tenant}",
                id,
                required(event, "tenantId", 100));
        if (!text(projection, "candidateId").isBlank()) candidates.project(projection);
    }
}
