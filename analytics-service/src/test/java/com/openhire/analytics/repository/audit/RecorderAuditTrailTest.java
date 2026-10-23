package com.openhire.analytics.repository.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.platform.audit.AuditRecorder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecorderAuditTrailTest {
    @Test
    void aReplayIsRecordedAsADeadLetterReplayedAuditEventWithoutTheFreeTextReason() {
        var written = new ArrayList<String>();
        var outbox = new AuditRecorder.Outbox() {
            @Override
            public void add(String eventId, String tenantId, String json) {
                written.add(tenantId + "|" + json);
            }

            @Override
            public List<Pending> pending(int limit) {
                return List.of();
            }

            @Override
            public void remove(String eventId) {}
        };
        var trail = new RecorderAuditTrail(new AuditRecorder(outbox, (key, json) -> {}, "analytics-service", true));

        trail.deadLetterReplayed(
                "t1", "admin-1", "Asha Admin", "communication.commands.v1.DLT:0:5", "communication.commands.v1");

        assertThat(written).singleElement().satisfies(entry -> {
            assertThat(entry).startsWith("t1|");
            assertThat(entry)
                    .contains("\"eventType\":\"audit.event.recorded.v1\"")
                    .contains("\"action\":\"DEAD_LETTER_REPLAYED\"")
                    .contains("\"type\":\"USER\"")
                    .contains("\"id\":\"admin-1\"")
                    .contains("\"type\":\"DeadLetter\"")
                    .contains("\"id\":\"communication.commands.v1.DLT:0:5\"")
                    .contains("\"originalTopic\":\"communication.commands.v1\"")
                    .contains("\"reasonCode\":\"OPERATOR_REPLAY\"");
        });
    }
}
