package com.openhire.employee.adapter.out.mongo;

import com.openhire.employee.application.port.EmployeePorts.EmployeeNumbers;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** One counter document per tenant and year, incremented atomically with {@code $inc}. */
@Component
public class MongoEmployeeNumbers implements EmployeeNumbers {
    static final String COLLECTION = "employee_counters";
    private final MongoTemplate mongo;

    public MongoEmployeeNumbers(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public long next(String tenantId, int year) {
        var key = tenantId + ":" + year;
        for (int attempt = 0; ; attempt++) {
            try {
                var row = mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(key)),
                        new Update().inc("seq", 1L),
                        FindAndModifyOptions.options().upsert(true).returnNew(true),
                        Document.class,
                        COLLECTION);
                return ((Number) row.get("seq")).longValue();
            } catch (DuplicateKeyException firstCreationRaced) {
                // Two upserts for a new key can collide once; the retry increments the document that won.
                if (attempt >= 3) throw firstCreationRaced;
            }
        }
    }
}
