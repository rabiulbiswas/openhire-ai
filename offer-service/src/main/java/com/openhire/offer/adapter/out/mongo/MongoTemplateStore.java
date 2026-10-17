package com.openhire.offer.adapter.out.mongo;

import com.openhire.offer.application.port.OfferPorts.TemplateStore;
import com.openhire.offer.domain.OfferClause;
import com.openhire.offer.domain.OfferTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoTemplateStore implements TemplateStore {
    private final MongoTemplate mongo;

    MongoTemplateStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<OfferTemplate> template(String tenantId, String key) {
        return Optional.ofNullable(mongo.findOne(byKey(tenantId, key), OfferTemplate.class));
    }

    @Override
    public List<OfferTemplate> templates(String tenantId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId)).with(Sort.by("key")), OfferTemplate.class);
    }

    @Override
    public OfferTemplate saveTemplate(OfferTemplate template) {
        return mongo.save(template);
    }

    @Override
    public Optional<OfferClause> clause(String tenantId, String key) {
        return Optional.ofNullable(mongo.findOne(byKey(tenantId, key), OfferClause.class));
    }

    @Override
    public List<OfferClause> clauses(String tenantId) {
        return mongo.find(Query.query(Criteria.where("tenantId").is(tenantId)).with(Sort.by("key")), OfferClause.class);
    }

    @Override
    public OfferClause saveClause(OfferClause clause) {
        return mongo.save(clause);
    }

    @Override
    public void seedTemplate(OfferTemplate template) {
        insertIfAbsent(template);
    }

    @Override
    public void seedClause(OfferClause clause) {
        insertIfAbsent(clause);
    }

    private void insertIfAbsent(Object value) {
        try {
            mongo.insert(value);
        } catch (DuplicateKeyException exists) {
            // Already seeded, possibly edited since; leave it alone.
        }
    }

    private static Query byKey(String tenantId, String key) {
        return Query.query(Criteria.where("tenantId").is(tenantId).and("key").is(key));
    }
}
