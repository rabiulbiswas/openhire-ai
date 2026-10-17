package com.openhire.approval.adapter.out.events;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "openhire.outbox.relay-enabled", havingValue = "true")
public class ApprovalOutboxRelay {
    private final ApprovalOutboxRepository events;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public ApprovalOutboxRelay(
            ApprovalOutboxRepository events, KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.events = events;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${openhire.outbox.poll-delay:1000}")
    void publishPending() {
        for (var value : events.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                kafka.send(value.topic(), value.aggregateId(), mapper.writeValueAsString(value.event()))
                        .get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
                value.markPublished();
                events.save(value);
            } catch (Exception exception) {
                LoggerFactory.getLogger(getClass()).warn("Approval event {} remains pending", value.id());
                return;
            }
        }
    }
}
