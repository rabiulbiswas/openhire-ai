package com.openhire.jobservice.repository.mongo;

import com.openhire.jobservice.adapter.out.mongo.OutboxEventDocument;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SpringDataOutboxRepositoryAccess {
    private final SpringDataOutboxRepository repository;

    public SpringDataOutboxRepositoryAccess(SpringDataOutboxRepository repository) {
        this.repository = repository;
    }

    public List<OutboxEventDocument> pending() {
        return repository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
    }

    public void save(OutboxEventDocument document) {
        repository.save(document);
    }
}
