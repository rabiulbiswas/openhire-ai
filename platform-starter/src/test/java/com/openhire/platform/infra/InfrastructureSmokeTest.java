package com.openhire.platform.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class InfrastructureSmokeTest {
    // mongo:8.0 exits on Linux kernel 6.19+ (MongoDB SERVER-121912), which Docker Desktop now ships.
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.2");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    @Test
    void mongoRedisAndKafkaAreReachable() throws Exception {
        try (var mongo = MongoClients.create(MONGO.getReplicaSetUrl())) {
            assertThat(mongo.listDatabaseNames().first()).isNotNull();
        }
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(REDIS.getHost(), REDIS.getMappedPort(6379)), 2_000);
            assertThat(socket.isConnected()).isTrue();
        }
        try (var admin = AdminClient.create(
                java.util.Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            assertThat(admin.listTopics().names().get()).isNotNull();
        }
    }
}
