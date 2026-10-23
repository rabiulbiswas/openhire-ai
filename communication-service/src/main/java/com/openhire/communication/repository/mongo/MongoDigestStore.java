package com.openhire.communication.repository.mongo;

import com.openhire.communication.domain.DigestItem;
import com.openhire.communication.service.port.CommunicationPorts.DigestStore;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoDigestStore implements DigestStore {
    private final MongoTemplate mongo;

    MongoDigestStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void add(DigestItem item) {
        mongo.insert(item);
    }

    @Override
    public List<DigestItem> take(String tenantId, String subject) {
        var items = mongo.find(
                Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("subject")
                                .is(subject))
                        .with(Sort.by("createdAt"))
                        .limit(200),
                DigestItem.class);
        if (!items.isEmpty())
            mongo.remove(
                    Query.query(Criteria.where("_id")
                            .in(items.stream().map(DigestItem::id).toList())),
                    DigestItem.class);
        return items;
    }
}
