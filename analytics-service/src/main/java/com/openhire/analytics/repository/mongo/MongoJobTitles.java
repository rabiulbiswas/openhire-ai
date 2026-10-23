package com.openhire.analytics.repository.mongo;

import com.openhire.analytics.service.port.AnalyticsPorts.JobTitles;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoJobTitles implements JobTitles {
    static final String COLLECTION = "job_titles";
    private final MongoTemplate mongo;

    MongoJobTitles(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<String> find(String tenantId, String jobId) {
        return Optional.ofNullable(mongo.findById(Docs.key(tenantId, jobId), Document.class, COLLECTION))
                .map(d -> d.getString("title"));
    }

    @Override
    public void put(String tenantId, String jobId, String title) {
        mongo.upsert(
                Query.query(Criteria.where("_id").is(Docs.key(tenantId, jobId))),
                new Update().set("tenantId", tenantId).set("jobId", jobId).set("title", title),
                COLLECTION);
    }
}
