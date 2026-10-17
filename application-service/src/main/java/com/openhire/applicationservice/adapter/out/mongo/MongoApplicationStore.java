package com.openhire.applicationservice.adapter.out.mongo;

import com.openhire.applicationservice.application.port.ApplicationStore;
import com.openhire.applicationservice.domain.ApplicationRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class MongoApplicationStore implements ApplicationStore {
    private final MongoTemplate mongo;

    public MongoApplicationStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<ApplicationRecord> find(String tenant, String id) {
        return Optional.ofNullable(mongo.findOne(
                        Query.query(
                                Criteria.where("tenantId").is(tenant).and("_id").is(id)),
                        ApplicationDocument.class))
                .map(ApplicationDocument::toDomain);
    }

    @Override
    public ApplicationRecord insertOrFind(ApplicationRecord record) {
        try {
            return mongo.insert(ApplicationDocument.from(record)).toDomain();
        } catch (DuplicateKeyException duplicate) {
            // Concurrent submission won the unique _id race; the application layer verifies its fingerprint.
            return find(record.tenantId(), record.id()).orElseThrow(() -> duplicate);
        }
    }

    @Override
    public ApplicationRecord save(ApplicationRecord record) {
        return mongo.save(ApplicationDocument.from(record)).toDomain();
    }

    @Override
    public List<ApplicationRecord> list(String tenant, String jobId, int page, int size) {
        var criteria = Criteria.where("tenantId").is(tenant);
        if (jobId != null && !jobId.isBlank()) criteria = criteria.and("jobId").is(jobId);
        var query = Query.query(criteria)
                .with(Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.asc("_id")))
                .skip((long) page * size)
                .limit(size);
        return mongo.find(query, ApplicationDocument.class).stream()
                .map(ApplicationDocument::toDomain)
                .toList();
    }
}
