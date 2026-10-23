package com.openhire.applicationservice.messaging.publisher;

import static com.openhire.support.Records.documents;

import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The shared embedded relay publishes {@code _events} to one topic, so the AI matching commands ride in their own
 * array on the same application document (written atomically with the screening result) and are relayed here to
 * {@code matching.commands.v1}, keyed by application id. At-least-once; the AI workers deduplicate.
 */
@Component
@ConditionalOnProperty(name = "openhire.outbox.relay-enabled", havingValue = "true")
class MatchingCommandRelay {
    static final String TOPIC = com.openhire.applicationservice.messaging.MatchingCommandContract.TOPIC;
    static final String FIELD = com.openhire.applicationservice.messaging.MatchingCommandContract.FIELD;

    private final MongoTemplate mongo;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    MatchingCommandRelay(MongoTemplate mongo, KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.mongo = mongo;
        this.kafka = kafka;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${openhire.outbox.poll-delay:1000}")
    void publish() {
        for (var row : mongo.find(
                Query.query(Criteria.where(FIELD + ".0").exists(true)).limit(50), Document.class, "submissions"))
            for (var command : documents(row.get(FIELD)))
                try {
                    kafka.send(TOPIC, row.getString("_id"), json.writeValueAsString(command))
                            .get(12, TimeUnit.SECONDS);
                    mongo.updateFirst(
                            Query.query(Criteria.where("_id").is(row.get("_id"))),
                            new Update().pull(FIELD, new Document("eventId", command.getString("eventId"))),
                            "submissions");
                } catch (Exception failure) {
                    LoggerFactory.getLogger(getClass())
                            .warn(
                                    "Matching command {} remains pending ({})",
                                    command.getString("eventId"),
                                    failure.getClass().getSimpleName());
                    if (failure instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    return;
                }
    }
}
