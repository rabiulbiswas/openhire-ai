package com.openhire.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactorTest {
    @Test
    @SuppressWarnings("unchecked")
    void masksPersonalKeysAtAnyDepthAndKeepsIdentifiers() {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("messageId", "m1");
        payload.put("resumeId", "r1");
        payload.put("candidateName", "Priya Das");
        payload.put("recipient", Map.of("email", "priya@example.com", "phone", "+911234567890"));
        payload.put("address", "12 MG Road");
        payload.put("resumeText", "ten years of Java");
        payload.put("note", "call back");
        payload.put("body", "Hello Priya");
        payload.put("channel", "EMAIL");
        payload.put("attempts", 3);
        payload.put("applicationIds", List.of("a1", "a2"));
        payload.put("answers", List.of(Map.of("name", "x", "questionId", "q1")));
        var input = Map.of("eventId", "e1", "tenantId", "t1", "payload", payload);

        var out = (Map<String, Object>) Redactor.redact(input);
        var redacted = (Map<String, Object>) out.get("payload");

        assertThat(out).containsEntry("eventId", "e1").containsEntry("tenantId", "t1");
        assertThat(redacted)
                .containsEntry("messageId", "m1")
                .containsEntry("resumeId", "r1")
                .containsEntry("candidateName", "***")
                .containsEntry("address", "***")
                .containsEntry("resumeText", "***")
                .containsEntry("note", "***")
                .containsEntry("body", "***")
                .containsEntry("recipient", Map.of("email", "***", "phone", "***"))
                .containsEntry("channel", "EMAIL")
                .containsEntry("attempts", 3)
                .containsEntry("applicationIds", List.of("a1", "a2"));
        assertThat(redacted.get("answers").toString()).contains("q1").doesNotContain("x");
    }

    @Test
    void masksEmailAddressesHiddenInOtherwiseHarmlessValues() {
        assertThat(Redactor.redact(Map.of("detail", "sent to priya@example.com failed")))
                .isEqualTo(Map.of("detail", "sent to *** failed"));
        assertThat(Redactor.text("boom for a.b@c.io")).isEqualTo("boom for ***");
        assertThat(Redactor.text(null)).isEmpty();
    }
}
