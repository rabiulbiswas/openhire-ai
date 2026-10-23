package com.openhire.jobservice.repository.mongo;

import com.openhire.jobservice.adapter.out.mongo.OutboxEventDocument;
import com.openhire.jobservice.service.port.OutboxWriter;
import com.openhire.platform.event.DomainEventEnvelope;
import org.springframework.stereotype.Repository;

@Repository
public class MongoOutboxAdapter implements OutboxWriter {
    private final SpringDataOutboxRepository repository;

    public MongoOutboxAdapter(SpringDataOutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(String topic, DomainEventEnvelope event) {
        repository.save(new OutboxEventDocument(topic, event));
    }
}
