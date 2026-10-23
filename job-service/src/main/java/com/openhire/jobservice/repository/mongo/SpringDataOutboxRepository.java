package com.openhire.jobservice.repository.mongo;

import com.openhire.jobservice.adapter.out.mongo.OutboxEventDocument;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

interface SpringDataOutboxRepository extends MongoRepository<OutboxEventDocument, String> {
    List<OutboxEventDocument> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
