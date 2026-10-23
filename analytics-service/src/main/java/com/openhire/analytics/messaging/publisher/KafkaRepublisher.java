package com.openhire.analytics.messaging.publisher;

import com.openhire.analytics.service.port.AnalyticsPorts.Republisher;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class KafkaRepublisher implements Republisher {
    private final KafkaTemplate<String, String> kafka;

    KafkaRepublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String topic, String key, String value, Map<String, String> headers) {
        var record = new ProducerRecord<String, String>(topic, null, key, value);
        headers.forEach(
                (name, text) -> record.headers().add(new RecordHeader(name, text.getBytes(StandardCharsets.UTF_8))));
        try {
            kafka.send(record).get(12, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The message could not be republished to " + topic, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while republishing", interrupted);
        }
    }
}
