package com.openhire.approval.repository.mongo;

import com.openhire.approval.adapter.out.events.ApprovalOutboxEvent;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ApprovalOutboxRepository extends MongoRepository<ApprovalOutboxEvent, String> {
    List<ApprovalOutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
