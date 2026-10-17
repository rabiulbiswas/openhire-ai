package com.openhire.aiworkers.matching.adapter.in.kafka;

import com.openhire.aiworkers.matching.application.MatchingService;
import com.openhire.aiworkers.matching.domain.MatchingRequest;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class MatchingCommandListener {
    private final MatchingService matching;
    private final ObjectMapper json;

    MatchingCommandListener(MatchingService matching, ObjectMapper json) {
        this.matching = matching;
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    @KafkaListener(
            topics = "matching.commands.v1",
            groupId = "ai-workers-matching-v1",
            concurrency = "${openhire.ai.concurrency:1}")
    void onCommand(String message) {
        var envelope = json.readValue(message, Map.class);
        if (!"matching.requested.v1".equals(envelope.get("eventType"))) return;
        matching.handle(MatchingRequest.fromEnvelope(envelope));
    }
}
