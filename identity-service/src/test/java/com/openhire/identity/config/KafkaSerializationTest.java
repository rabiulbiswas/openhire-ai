package com.openhire.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class KafkaSerializationTest {
    @Test
    void configuredSerializerWorksWithBoot4JacksonRuntime() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String name = yaml.getObject().getProperty("spring.kafka.producer.value-serializer");
        try (var serializer =
                (Serializer<Object>) Class.forName(name).getConstructor().newInstance()) {
            assertThat(new String(
                            serializer.serialize("fixture", java.util.Map.of("eventType", "ApiKeyCreated")),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .contains("ApiKeyCreated");
        }
    }
}
