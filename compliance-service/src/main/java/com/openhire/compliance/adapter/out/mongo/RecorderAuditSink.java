package com.openhire.compliance.adapter.out.mongo;

import com.openhire.compliance.application.port.CompliancePorts.AuditSink;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import org.springframework.stereotype.Component;

/** compliance-service audits its own actions like every other service: through the shared recorder. */
@Component
class RecorderAuditSink implements AuditSink {
    private final AuditRecorder recorder;

    RecorderAuditSink(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void record(AuditRecord record) {
        recorder.record(record);
    }
}
