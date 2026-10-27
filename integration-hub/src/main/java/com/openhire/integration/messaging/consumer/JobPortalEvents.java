package com.openhire.integration.messaging.consumer;

import com.openhire.integration.service.JobPortals;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JobPortalEvents {
    private final JobPortals portals;
    private final ObjectMapper json;

    public JobPortalEvents(JobPortals portals, ObjectMapper json) {
        this.portals = portals;
        this.json = json;
    }

    @KafkaListener(topics = "job.events.v1", groupId = "integration-job-portals-v1")
    public void receive(String message) {
        var event = json.readValue(message, Map.class);
        if (!"job.closed.v1".equals(event.get("eventType")) && !"job.paused.v1".equals(event.get("eventType"))) return;
        portals.lifecycleChanged((String) event.get("tenantId"), (String) event.get("aggregateId"));
    }
}
