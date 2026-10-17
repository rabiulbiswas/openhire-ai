package com.openhire.jobservice.adapter.out.mongo;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

interface SpringDataOutboxRepository extends MongoRepository<OutboxEventDocument, String> {
    List<OutboxEventDocument> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
