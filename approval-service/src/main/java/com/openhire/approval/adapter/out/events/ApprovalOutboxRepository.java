package com.openhire.approval.adapter.out.events;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

interface ApprovalOutboxRepository extends MongoRepository<ApprovalOutboxEvent, String> {
    List<ApprovalOutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
