package com.openhire.identity.adapter.out.events;

import com.openhire.identity.application.port.IdentityEventPublisher;
import com.openhire.platform.event.DomainEventEnvelope;
import java.time.Clock;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class IdentityEventConfiguration {
    @Bean
    @ConditionalOnProperty(name = "openhire.events.publisher", havingValue = "log", matchIfMissing = true)
    IdentityEventPublisher loggingEvents(Clock clock) {
        var logger = LoggerFactory.getLogger("openhire.identity.events");
        return (type, tenant, aggregate, payload) ->
                logger.info("event={} tenant={} aggregate={} payload={}", type, tenant, aggregate, payload);
    }

    @Bean
    @ConditionalOnProperty(name = "openhire.events.publisher", havingValue = "kafka")
    IdentityEventPublisher kafkaEvents(KafkaTemplate<String, Object> kafka, Clock clock) {
        return (type, tenant, aggregate, payload) -> kafka.send(
                "openhire.identity.events",
                tenant,
                DomainEventEnvelope.create(type, tenant, "identity", aggregate, null, Map.copyOf(payload)));
    }
}
