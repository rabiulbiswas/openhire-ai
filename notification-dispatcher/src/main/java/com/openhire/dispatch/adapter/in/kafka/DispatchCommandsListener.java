package com.openhire.dispatch.adapter.in.kafka;

import com.openhire.dispatch.application.DispatchService;
import com.openhire.dispatch.application.port.ChannelAdapter.TransientFailure;
import com.openhire.dispatch.domain.Delivery;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes dispatch commands. A transient provider failure moves the command through retry topics
 * (communication.commands.v1-retry-0..2, delays from configuration) and finally to the -dlt topic,
 * where the delivery is marked FAILED and becomes visible to operators.
 */
@Component
class DispatchCommandsListener {
    private static final Logger LOG = LoggerFactory.getLogger(DispatchCommandsListener.class);
    private final DispatchService dispatch;
    private final ObjectMapper json;
    private final Clock clock;

    DispatchCommandsListener(DispatchService dispatch, ObjectMapper json, Clock clock) {
        this.dispatch = dispatch;
        this.json = json;
        this.clock = clock;
    }

    private com.openhire.support.privacy.Tombstones tombstones;

    /** Optional so the listener can be built without the privacy framework in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    @RetryableTopic(
            attempts = "${openhire.dispatch.retry.attempts:4}",
            backOff =
                    @BackOff(
                            delayString = "${openhire.dispatch.retry.first-delay-ms:10000}",
                            multiplierString = "${openhire.dispatch.retry.multiplier:6}",
                            maxDelayString = "${openhire.dispatch.retry.max-delay-ms:600000}"),
            include = TransientFailure.class,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "true")
    @KafkaListener(topics = "communication.commands.v1", groupId = "notification-dispatcher-v1")
    void onCommand(String message) {
        var delivery = parse(message);
        if (delivery == null) return;
        // A late command for an erased candidate's conversation must not recreate a delivery.
        if (delivery.conversationId() != null
                && tombstones != null
                && tombstones.isErased(delivery.tenantId(), delivery.conversationId())) return;
        dispatch.accept(delivery);
    }

    @DltHandler
    void onExhausted(String message, @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        var delivery = parse(message);
        if (delivery == null) return;
        LOG.warn("Delivery {} failed after all retries: {}", delivery.id(), error);
        dispatch.exhausted(delivery.id(), error == null ? "provider unavailable" : error);
    }

    @SuppressWarnings("unchecked")
    private Delivery parse(String message) {
        Map<String, Object> event = json.readValue(message, Map.class);
        if (!"communication.message.requested.v1".equals(event.get("eventType"))) return null;
        if (!(event.get("payload") instanceof Map<?, ?> raw)) return null;
        var payload = (Map<String, Object>) raw;
        var to = payload.get("recipient") instanceof Map<?, ?> value
                ? (Map<String, Object>) value
                : Map.<String, Object>of();
        Delivery.QuietHours quiet = null;
        if (payload.get("quietHours") instanceof Map<?, ?> value) {
            var hours = (Map<String, Object>) value;
            quiet = new Delivery.QuietHours(
                    (String) hours.get("from"), (String) hours.get("to"), (String) hours.get("timezone"));
        }
        Delivery.Category category;
        try {
            category = Delivery.Category.valueOf(String.valueOf(payload.get("category")));
        } catch (IllegalArgumentException unknown) {
            category = Delivery.Category.NOTIFICATION;
        }
        var delivery = Delivery.request(
                String.valueOf(payload.get("messageId")),
                (String) event.get("tenantId"),
                (String) payload.get("audience"),
                (String) payload.get("channel"),
                (String) payload.get("fallbackChannel"),
                new Delivery.Recipient((String) to.get("name"), (String) to.get("email"), (String) to.get("phone")),
                (String) payload.get("subject"),
                (String) payload.get("body"),
                category,
                quiet,
                clock.instant());
        // The command's correlation id is the candidate conversation for candidate messages.
        if ("CANDIDATE".equals(delivery.audience()) && event.get("correlationId") instanceof String correlation)
            delivery.inConversation(correlation);
        return delivery;
    }
}
