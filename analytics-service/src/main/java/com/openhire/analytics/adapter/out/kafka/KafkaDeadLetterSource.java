package com.openhire.analytics.adapter.out.kafka;

import com.openhire.analytics.application.port.AnalyticsPorts.DeadLetterSource;
import com.openhire.analytics.domain.DeadLetterRecord;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads {@code *.DLT} topics with a standalone consumer: partitions are assigned by hand, offsets are never
 * committed, and the group id is unique per read, so no application group is ever disturbed.
 */
@Component
class KafkaDeadLetterSource implements DeadLetterSource {
    private static final Pattern DLT = Pattern.compile("(?i).+([.-])dlt");
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int LONG_BYTES = 8;
    private static final int INT_BYTES = 4;

    private final String bootstrap;

    KafkaDeadLetterSource(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.bootstrap = bootstrap;
    }

    static boolean isDeadLetterTopic(String topic) {
        return topic != null && DLT.matcher(topic).matches();
    }

    @Override
    public List<DeadLetterRecord> recent(int perPartition) {
        try (var admin = Admin.create(Map.<String, Object>of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrap,
                        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                        5000,
                        AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                        8000));
                var consumer = consumer()) {
            var topics = admin.listTopics().names().get(10, TimeUnit.SECONDS).stream()
                    .filter(KafkaDeadLetterSource::isDeadLetterTopic)
                    .toList();
            if (topics.isEmpty()) return List.of();
            var partitions = new ArrayList<TopicPartition>();
            admin.describeTopics(topics)
                    .allTopicNames()
                    .get(10, TimeUnit.SECONDS)
                    .forEach((name, description) -> description
                            .partitions()
                            .forEach(p -> partitions.add(new TopicPartition(name, p.partition()))));
            consumer.assign(partitions);
            var begin = consumer.beginningOffsets(partitions);
            var end = consumer.endOffsets(partitions);
            var starts = new HashMap<TopicPartition, Long>();
            var targets = new HashMap<TopicPartition, Long>();
            for (var partition : partitions) {
                long last = end.get(partition);
                long first = Math.max(begin.get(partition), last - perPartition);
                if (first >= last) continue;
                starts.put(partition, first);
                targets.put(partition, last);
            }
            if (targets.isEmpty()) return List.of();
            // Empty partitions have no position to read from, so only the ones with records stay assigned.
            consumer.assign(targets.keySet());
            starts.forEach(consumer::seek);
            var result = new ArrayList<DeadLetterRecord>();
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (!reached(consumer, targets) && System.nanoTime() < deadline)
                for (var record : consumer.poll(Duration.ofMillis(300)))
                    if (record.offset() < targets.get(new TopicPartition(record.topic(), record.partition())))
                        result.add(map(record));
            return result;
        } catch (ExecutionException | java.util.concurrent.TimeoutException | RuntimeException failure) {
            throw unavailable(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable(interrupted);
        }
    }

    @Override
    public Optional<DeadLetterRecord> find(String topic, int partition, long offset) {
        if (!isDeadLetterTopic(topic)) return Optional.empty();
        try (var consumer = consumer()) {
            var tp = new TopicPartition(topic, partition);
            consumer.assign(List.of(tp));
            if (offset < consumer.beginningOffsets(List.of(tp)).get(tp)
                    || offset >= consumer.endOffsets(List.of(tp)).get(tp)) return Optional.empty();
            consumer.seek(tp, offset);
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline)
                for (var record : consumer.poll(Duration.ofMillis(300)))
                    if (record.offset() == offset) return Optional.of(map(record));
            return Optional.empty();
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    private static boolean reached(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> targets) {
        return targets.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(
                Map.<String, Object>of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrap,
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "analytics-dlq-reader-" + UUID.randomUUID(),
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        false,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "none",
                        ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                        500),
                new StringDeserializer(),
                new StringDeserializer());
    }

    private static DeadLetterRecord map(ConsumerRecord<String, String> record) {
        var headers = new HashMap<String, String>();
        for (Header header : record.headers()) headers.put(header.key(), decode(header.key(), header.value()));
        return new DeadLetterRecord(
                record.topic(),
                record.partition(),
                record.offset(),
                Instant.ofEpochMilli(record.timestamp()),
                record.key(),
                record.value(),
                headers);
    }

    /** Spring writes the original partition as an int and the offset and timestamp as longs; the rest is text. */
    static String decode(String name, byte[] value) {
        if (value == null) return "";
        if (name.equals("kafka_dlt-original-partition") && value.length == INT_BYTES)
            return String.valueOf(ByteBuffer.wrap(value).getInt());
        if ((name.equals("kafka_dlt-original-offset") || name.equals("kafka_dlt-original-timestamp"))
                && value.length == LONG_BYTES)
            return String.valueOf(ByteBuffer.wrap(value).getLong());
        return new String(value, StandardCharsets.UTF_8);
    }

    private static ResponseStatusException unavailable(Exception failure) {
        org.slf4j.LoggerFactory.getLogger(KafkaDeadLetterSource.class)
                .warn("Dead-letter topics could not be read", failure);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka is unavailable", failure);
    }
}
