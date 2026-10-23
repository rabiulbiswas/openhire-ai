package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTypes.Provenance;
import com.openhire.aigateway.service.port.AiPorts.BudgetAlert;
import com.openhire.aigateway.service.port.AiPorts.RequestLog;
import com.openhire.aigateway.service.port.AiPorts.RequestRecord;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists one {@code ai_requests} row per call (provenance, outcome, guardrail results, prompt hash). The row never
 * holds prompt text, resume text or candidate names; the repository adds the completion event to the outbox.
 */
public class ProvenanceWriter {
    private static final Logger log = LoggerFactory.getLogger(ProvenanceWriter.class);
    private final RequestLog requests;

    public ProvenanceWriter(RequestLog requests) {
        this.requests = requests;
    }

    public void write(
            String tenantId,
            Provenance provenance,
            String outcome,
            String errorCode,
            Map<String, Object> guardrails,
            String promptHash,
            String purpose,
            BudgetAlert alert) {
        try {
            requests.save(new RequestRecord(
                    tenantId, provenance, outcome, errorCode, guardrails, promptHash, purpose, alert));
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record provenance for request {} ({})",
                    provenance.requestId(),
                    e.getClass().getSimpleName());
        }
    }
}
