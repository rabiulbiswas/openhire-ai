package com.openhire.analytics.domain;

import java.time.Instant;
import java.util.Map;

/** A message as stored on a dead-letter topic, with Spring's {@code kafka_dlt-*} headers decoded to text. */
public record DeadLetterRecord(
        String topic,
        int partition,
        long offset,
        Instant timestamp,
        String key,
        String value,
        Map<String, String> headers) {
    public String id() {
        return topic + ":" + partition + ":" + offset;
    }

    public String header(String name) {
        var value = headers.get(name);
        return value == null ? "" : value;
    }
}
