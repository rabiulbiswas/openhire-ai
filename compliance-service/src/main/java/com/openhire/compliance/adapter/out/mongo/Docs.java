package com.openhire.compliance.adapter.out.mongo;

import com.mongodb.client.FindIterable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;

final class Docs {
    private Docs() {}

    static Map<String, Object> map(Document document) {
        return document == null ? null : new LinkedHashMap<>(document);
    }

    static List<Map<String, Object>> list(FindIterable<Document> found) {
        var out = new ArrayList<Map<String, Object>>();
        for (var document : found) out.add(map(document));
        return out;
    }

    static Document document(Map<String, Object> map) {
        return new Document(map);
    }

    static boolean duplicate(RuntimeException failure) {
        return failure instanceof com.mongodb.MongoWriteException write
                ? write.getError().getCode() == 11000
                : failure instanceof org.springframework.dao.DuplicateKeyException
                        || failure instanceof com.mongodb.DuplicateKeyException;
    }
}
