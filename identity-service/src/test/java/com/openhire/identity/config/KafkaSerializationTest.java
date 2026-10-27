package com.openhire.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class KafkaSerializationTest {
    @Test
    void configuredSerializerWorksWithBoot4JacksonRuntime() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String name = yaml.getObject().getProperty("spring.kafka.producer.value-serializer");
        try (var serializer =
                (Serializer<String>) Class.forName(name).getConstructor().newInstance()) {
            var encoded = "{\"eventType\":\"audit.event.recorded.v1\",\"tenantId\":\"fixture\"}";
            var wire = new String(
                    serializer.serialize("audit.events.v1", encoded), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(wire).isEqualTo(encoded);
            assertThat(new ObjectMapper().readTree(wire).isObject()).isTrue();
        }
    }

    @Test
    void domainPublisherAlsoEncodesItsEnvelopeExactlyOnce() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var json = new ObjectMapper();
        var publisher = new IdentityEventConfiguration().kafkaEvents(kafka, java.time.Clock.systemUTC(), json);
        publisher.publish("identity.user.invited.v1", "fixture", "member-1", java.util.Map.of("fixture", true));
        var body = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("openhire.identity.events"), eq("fixture"), body.capture());
        assertThat(json.readTree(body.getValue()).isObject()).isTrue();
        assertThat(json.readTree(body.getValue()).get("eventType").asString()).isEqualTo("identity.user.invited.v1");
    }
}
