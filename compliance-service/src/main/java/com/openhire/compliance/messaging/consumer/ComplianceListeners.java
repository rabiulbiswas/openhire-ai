package com.openhire.compliance.messaging.consumer;

import com.openhire.compliance.service.AuditLedger;
import com.openhire.compliance.service.PrivacyService;
import com.openhire.compliance.service.Projections;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Failures propagate to the shared Kafka error handler (retry, then dead letter), so nothing is silently dropped.
 */
@Component
class ComplianceListeners {
    private final AuditLedger ledger;
    private final Projections projections;
    private final PrivacyService privacy;
    private final ObjectMapper json;

    ComplianceListeners(AuditLedger ledger, Projections projections, PrivacyService privacy, ObjectMapper json) {
        this.ledger = ledger;
        this.projections = projections;
        this.privacy = privacy;
        this.json = json;
    }

    @KafkaListener(topics = "audit.events.v1", groupId = "compliance-audit-v1")
    void onAudit(String message) {
        ledger.ingest(parse(message));
    }

    @KafkaListener(
            topics = {
                "application.events.v1",
                "assessment.events.v1",
                "integrity.events.v1",
                "offer.events.v1",
                "employee.events.v1"
            },
            groupId = "compliance-projections-v1")
    void onActivity(String message) {
        projections.accept(parse(message));
    }

    @KafkaListener(topics = "privacy.acks.v1", groupId = "compliance-privacy-acks-v1")
    void onAck(String message) {
        privacy.onAck(parse(message));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String message) {
        return json.readValue(message, Map.class);
    }
}
