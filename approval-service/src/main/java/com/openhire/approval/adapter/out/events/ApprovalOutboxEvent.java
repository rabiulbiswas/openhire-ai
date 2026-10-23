package com.openhire.approval.adapter.out.events;

import com.openhire.platform.event.DomainEventEnvelope;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("outbox")
@CompoundIndex(name = "unpublished_events", def = "{'publishedAt':1,'createdAt':1}")
public class ApprovalOutboxEvent {
    @Id
    private String id;

    private String topic;
    private String aggregateId;
    private DomainEventEnvelope event;
    private Instant createdAt;
    private Instant publishedAt;

    protected ApprovalOutboxEvent() {}

    public ApprovalOutboxEvent(String topic, DomainEventEnvelope event) {
        this.id = event.eventId().toString();
        this.topic = topic;
        this.aggregateId = event.aggregateId();
        this.event = event;
        this.createdAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public String topic() {
        return topic;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public DomainEventEnvelope event() {
        return event;
    }

    public void markPublished() {
        publishedAt = Instant.now();
    }
}
