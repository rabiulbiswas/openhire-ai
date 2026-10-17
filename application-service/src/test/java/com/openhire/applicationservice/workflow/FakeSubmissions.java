package com.openhire.applicationservice.workflow;

import static com.openhire.support.Records.doc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** A MongoTemplate stand-in for the submissions collection that applies $set, $unset and $push for real. */
final class FakeSubmissions {
    final Map<String, Document> rows = new LinkedHashMap<>();
    final List<Document> updates = new ArrayList<>();
    final MongoTemplate mongo = mock(MongoTemplate.class);

    FakeSubmissions() {
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("submissions")))
                .thenAnswer(call ->
                        rows.get(((Query) call.getArgument(0)).getQueryObject().get("_id")));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq("submissions")))
                .thenAnswer(call -> {
                    var query = ((Query) call.getArgument(0)).getQueryObject();
                    var row = rows.get(query.get("_id"));
                    if (row == null
                            || (query.containsKey("revision")
                                    && !query.get("revision").equals(row.get("revision"))))
                        return UpdateResult.acknowledged(0, 0L, null);
                    var update = ((Update) call.getArgument(1)).getUpdateObject();
                    updates.add(update);
                    apply(row, update);
                    return UpdateResult.acknowledged(1, 1L, null);
                });
    }

    Document put(Document row) {
        rows.put(row.getString("_id"), row);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static void apply(Document row, Document update) {
        doc(update.get("$set")).forEach((path, value) -> set(row, path, value));
        doc(update.get("$unset")).forEach((path, ignored) -> set(row, path, null));
        doc(update.get("$inc"))
                .forEach((key, by) -> row.put(key, ((Number) row.get(key)).longValue() + ((Number) by).longValue()));
        doc(update.get("$push")).forEach((key, value) -> {
            var list = new ArrayList<Object>(row.get(key) instanceof List<?> l ? l : List.of());
            if (value instanceof Document d && d.containsKey("$each")) {
                list.addAll((List<Object>) d.get("$each"));
                if (d.get("$slice") instanceof Integer slice && slice < 0 && list.size() > -slice)
                    list = new ArrayList<>(list.subList(list.size() + slice, list.size()));
            } else list.add(value);
            row.put(key, list);
        });
    }

    private static void set(Document row, String path, Object value) {
        var parts = path.split("\\.");
        var target = row;
        for (int i = 0; i < parts.length - 1; i++) {
            var next = target.get(parts[i]);
            if (!(next instanceof Document)) {
                next = new Document();
                target.put(parts[i], next);
            }
            target = (Document) next;
        }
        if (value == null) target.remove(parts[parts.length - 1]);
        else target.put(parts[parts.length - 1], value);
    }
}
