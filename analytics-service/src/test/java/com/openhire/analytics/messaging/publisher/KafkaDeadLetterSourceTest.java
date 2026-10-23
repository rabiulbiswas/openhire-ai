package com.openhire.analytics.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class KafkaDeadLetterSourceTest {
    @Test
    void onlyDeadLetterTopicsCanBeRead() {
        assertThat(KafkaDeadLetterSource.isDeadLetterTopic("application.events.v1.DLT"))
                .isTrue();
        assertThat(KafkaDeadLetterSource.isDeadLetterTopic("communication.commands.v1-dlt"))
                .isTrue();
        assertThat(KafkaDeadLetterSource.isDeadLetterTopic("application.events.v1"))
                .isFalse();
        assertThat(KafkaDeadLetterSource.isDeadLetterTopic("audit.events.v1")).isFalse();
        assertThat(KafkaDeadLetterSource.isDeadLetterTopic(".DLT")).isFalse();
        assertThat(KafkaDeadLetterSource.isDeadLetterTopic(null)).isFalse();
    }

    @Test
    void springsBinaryHeadersAreDecodedAndTheRestIsText() {
        assertThat(KafkaDeadLetterSource.decode(
                        "kafka_dlt-original-partition",
                        ByteBuffer.allocate(4).putInt(3).array()))
                .isEqualTo("3");
        assertThat(KafkaDeadLetterSource.decode(
                        "kafka_dlt-original-offset",
                        ByteBuffer.allocate(8).putLong(1234567890123L).array()))
                .isEqualTo("1234567890123");
        assertThat(KafkaDeadLetterSource.decode(
                        "kafka_dlt-original-timestamp",
                        ByteBuffer.allocate(8).putLong(42L).array()))
                .isEqualTo("42");
        assertThat(KafkaDeadLetterSource.decode("kafka_dlt-original-topic", "orders".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("orders");
        assertThat(KafkaDeadLetterSource.decode("kafka_dlt-original-offset", "55".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("55");
        assertThat(KafkaDeadLetterSource.decode("anything", null)).isEmpty();
    }
}
