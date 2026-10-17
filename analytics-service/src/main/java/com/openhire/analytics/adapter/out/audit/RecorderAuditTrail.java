package com.openhire.analytics.adapter.out.audit;

import com.openhire.analytics.application.port.AnalyticsPorts.AuditTrail;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import org.springframework.stereotype.Component;

/** Records replays with the shared recorder. The reviewer's free-text reason stays in {@code dlq_replays}. */
@Component
class RecorderAuditTrail implements AuditTrail {
    private final AuditRecorder recorder;

    RecorderAuditTrail(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void deadLetterReplayed(String tenantId, String actorId, String actorName, String id, String originalTopic) {
        recorder.record(AuditRecord.of(tenantId, "DEAD_LETTER_REPLAYED")
                .user(actorId, actorName)
                .resource("DeadLetter", id)
                .detail("originalTopic", originalTopic)
                .detail("reasonCode", "OPERATOR_REPLAY")
                .build());
    }
}
