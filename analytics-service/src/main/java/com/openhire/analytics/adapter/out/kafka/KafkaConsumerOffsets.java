package com.openhire.analytics.adapter.out.kafka;

import com.openhire.analytics.application.port.AnalyticsPorts.ConsumerOffsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Committed offsets of every consumer group against the log end offsets, through the Kafka admin client. */
@Component
class KafkaConsumerOffsets implements ConsumerOffsets {
    private static final long TIMEOUT_SECONDS = 10;
    private final String bootstrap;

    KafkaConsumerOffsets(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public List<PartitionOffset> offsets() {
        try (var admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrap,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                5000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                8000))) {
            var committed = new HashMap<String, Map<TopicPartition, Long>>();
            var wanted = new HashMap<TopicPartition, OffsetSpec>();
            for (var group : admin.listGroups(ListGroupsOptions.forConsumerGroups())
                    .all()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                var offsets = admin.listConsumerGroupOffsets(group.groupId())
                        .partitionsToOffsetAndMetadata()
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                var perPartition = new HashMap<TopicPartition, Long>();
                offsets.forEach((partition, offset) -> {
                    if (offset != null) {
                        perPartition.put(partition, offset.offset());
                        wanted.put(partition, OffsetSpec.latest());
                    }
                });
                if (!perPartition.isEmpty()) committed.put(group.groupId(), perPartition);
            }
            var ends = wanted.isEmpty()
                    ? Map.<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo>of()
                    : admin.listOffsets(wanted).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var result = new ArrayList<PartitionOffset>();
            committed.forEach((group, partitions) -> partitions.forEach((partition, offset) -> {
                var end = ends.get(partition);
                if (end != null)
                    result.add(
                            new PartitionOffset(group, partition.topic(), partition.partition(), offset, end.offset()));
            }));
            return result;
        } catch (ExecutionException | java.util.concurrent.TimeoutException | RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka is unavailable", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted", interrupted);
        }
    }
}
