package com.openhire.approval.adapter.out.events;

import com.openhire.approval.application.port.ApprovalEventPublisher;
import com.openhire.platform.event.DomainEventEnvelope;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApprovalEventConfiguration {
    @Bean
    ApprovalEventPublisher outboxPublisher(ApprovalOutboxRepository events) {
        return (type, tenant, id, payload) -> events.save(new ApprovalOutboxEvent(
                "approval.events.v1",
                DomainEventEnvelope.create(type, tenant, "Approval", id, null, Map.copyOf(payload))));
    }
}
