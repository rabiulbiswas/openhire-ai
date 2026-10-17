package com.openhire.support.privacy;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Every service that has a {@link PrivacyParticipant} answers compliance-service's export and erasure requests. A
 * failure propagates to the shared Kafka error handler (retry, then dead letter), so a request is never silently lost.
 */
@Component
class PrivacyRequestListener {
    private final PrivacyHandler handler;
    private final KafkaTemplate<String, String> kafka;

    PrivacyRequestListener(
            ObjectProvider<PrivacyParticipant> participants,
            KafkaTemplate<String, String> kafka,
            @Value("${spring.application.name:unknown-service}") String service) {
        this.handler = new PrivacyHandler(participants.orderedStream().toList(), service);
        this.kafka = kafka;
    }

    @KafkaListener(topics = PrivacyHandler.REQUEST_TOPIC, groupId = "${spring.application.name}-privacy-v1")
    void onRequest(String message) throws Exception {
        for (var ack : handler.handle(message))
            kafka.send(PrivacyHandler.ACK_TOPIC, ack.key(), ack.json()).get(12, TimeUnit.SECONDS);
    }
}
