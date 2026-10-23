package com.openhire.jobservice.messaging.publisher;

import com.openhire.jobservice.adapter.out.mongo.OutboxEventDocument;
import com.openhire.jobservice.repository.mongo.SpringDataOutboxRepositoryAccess;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "openhire.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final SpringDataOutboxRepositoryAccess repository;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public OutboxRelay(
            SpringDataOutboxRepositoryAccess repository,
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${openhire.outbox.poll-delay:1000}")
    void publishPending() {
        for (OutboxEventDocument document : repository.pending()) {
            try {
                var payload = objectMapper.writeValueAsString(document.event());
                kafka.send(document.topic(), document.aggregateId(), payload)
                        .get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
                document.markPublished();
                repository.save(document);
            } catch (JacksonException exception) {
                throw new IllegalStateException("Could not serialize outbox event " + document.id(), exception);
            } catch (Exception exception) {
                log.warn("Kafka is unavailable; outbox event {} remains pending", document.id());
                return;
            }
        }
    }
}
