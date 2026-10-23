package com.openhire.offer.repository.mongo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.openhire.support.privacy.Tombstones;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** A MongoTemplate stand-in over in-memory collections for the Document based find, update and remove calls. */
final class InMemoryMongo {
    final Map<String, List<Document>> collections = new LinkedHashMap<>();
    final MongoTemplate template = mock(MongoTemplate.class);

    InMemoryMongo() {
        when(template.find(any(Query.class), eq(Document.class), anyString())).thenAnswer(call -> {
            var rows = new ArrayList<Document>();
            for (var row : collection(call.getArgument(2))) if (matches(row, query(call))) rows.add(copy(row));
            return rows;
        });
        when(template.findOne(any(Query.class), eq(Document.class), anyString()))
                .thenAnswer(call -> {
                    for (var row : collection(call.getArgument(2))) if (matches(row, query(call))) return copy(row);
                    return null;
                });
        when(template.exists(any(Query.class), anyString())).thenAnswer(call -> {
            for (var row : collection(call.getArgument(1))) if (matches(row, query(call))) return true;
            return false;
        });
        when(template.updateFirst(any(Query.class), any(Update.class), anyString()))
                .thenAnswer(call -> update(call.getArgument(2), query(call), call.getArgument(1), false));
        when(template.updateMulti(any(Query.class), any(Update.class), anyString()))
                .thenAnswer(call -> update(call.getArgument(2), query(call), call.getArgument(1), true));
        when(template.remove(any(Query.class), anyString())).thenAnswer(call -> {
            var rows = collection(call.getArgument(1));
            int before = rows.size();
            rows.removeIf(row -> matches(row, query(call)));
            return DeleteResult.acknowledged(before - rows.size());
        });
    }

    List<Document> collection(String name) {
        return collections.computeIfAbsent(name, key -> new ArrayList<>());
    }

    Document put(String name, Document row) {
        collection(name).add(row);
        return row;
    }

    Document one(String name, String id) {
        return collection(name).stream()
                .filter(row -> id.equals(row.get("_id")))
                .findFirst()
                .orElse(null);
    }

    /** A Tombstones double that remembers marks in memory. */
    static Tombstones tombstones() {
        var marked = new HashSet<String>();
        var tombstones = mock(Tombstones.class);
        org.mockito.Mockito.doAnswer(call -> marked.add(call.getArgument(0) + "|" + call.getArgument(1)))
                .when(tombstones)
                .mark(anyString(), anyString(), anyString());
        when(tombstones.isErased(anyString(), anyString()))
                .thenAnswer(call -> marked.contains(call.getArgument(0) + "|" + call.getArgument(1)));
        return tombstones;
    }

    private static Document query(org.mockito.invocation.InvocationOnMock call) {
        return ((Query) call.getArgument(0)).getQueryObject();
    }

    private UpdateResult update(String name, Document query, Update update, boolean multi) {
        long matched = 0;
        for (var row : collection(name)) {
            if (!matches(row, query)) continue;
            matched++;
            apply(row, update.getUpdateObject());
            if (!multi) break;
        }
        return UpdateResult.acknowledged(matched, matched, null);
    }

    @SuppressWarnings("unchecked")
    private static void apply(Document row, Document update) {
        for (var entry : update.entrySet()) {
            var fields = (Map<String, Object>) entry.getValue();
            switch (entry.getKey()) {
                case "$set" -> fields.forEach((path, value) -> set(row, path, value, false));
                case "$unset" -> fields.forEach((path, ignored) -> set(row, path, null, true));
                case "$inc" ->
                    fields.forEach((path, by) -> {
                        var current = values(row, path);
                        double base = current.isEmpty() ? 0 : ((Number) current.get(0)).doubleValue();
                        set(row, path, (long) (base + ((Number) by).doubleValue()), false);
                    });
                case "$pull" ->
                    fields.forEach((path, condition) -> {
                        if (row.get(path) instanceof List<?> list)
                            row.put(
                                    path,
                                    list.stream()
                                            .filter(item -> !(item instanceof Map<?, ?> map
                                                    && ((Map<String, Object>) condition)
                                                            .entrySet().stream()
                                                                    .allMatch(c ->
                                                                            same(map.get(c.getKey()), c.getValue()))))
                                            .toList());
                    });
                default -> throw new UnsupportedOperationException(entry.getKey());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void set(Document row, String path, Object value, boolean remove) {
        var parts = path.split("\\.");
        Map<String, Object> target = row;
        for (int i = 0; i < parts.length - 1; i++) {
            var next = target.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                if (remove) return;
                next = new Document();
                target.put(parts[i], next);
            }
            target = (Map<String, Object>) next;
        }
        if (remove) target.remove(parts[parts.length - 1]);
        else target.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    static boolean matches(Map<String, Object> row, Map<String, Object> query) {
        for (var entry : query.entrySet()) {
            var key = entry.getKey();
            if (key.equals("$and")) {
                for (var part : (List<Map<String, Object>>) entry.getValue()) if (!matches(row, part)) return false;
            } else if (key.equals("$or")) {
                boolean any = false;
                for (var part : (List<Map<String, Object>>) entry.getValue()) any |= matches(row, part);
                if (!any) return false;
            } else if (!field(values(row, key), entry.getValue())) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean field(List<Object> found, Object expected) {
        if (expected instanceof Map<?, ?> operators
                && operators.keySet().stream().allMatch(k -> k.toString().startsWith("$"))) {
            for (var operator : ((Map<String, Object>) operators).entrySet()) {
                var argument = operator.getValue();
                boolean ok =
                        switch (operator.getKey()) {
                            case "$in" ->
                                found.stream().anyMatch(value -> contains((Iterable<Object>) argument, value));
                            case "$nin" ->
                                found.stream().noneMatch(value -> contains((Iterable<Object>) argument, value));
                            case "$ne" -> found.stream().noneMatch(value -> same(value, argument));
                            case "$exists" -> found.isEmpty() != (Boolean) argument;
                            case "$gt" -> compare(found, argument) > 0;
                            case "$gte" -> compare(found, argument) >= 0;
                            case "$lt" -> compare(found, argument) < 0 && !found.isEmpty();
                            case "$lte" -> compare(found, argument) <= 0 && !found.isEmpty();
                            default -> throw new UnsupportedOperationException(operator.getKey());
                        };
                if (!ok) return false;
            }
            return true;
        }
        if (found.isEmpty()) return expected == null;
        return found.stream().anyMatch(value -> same(value, expected));
    }

    @SuppressWarnings("unchecked")
    private static int compare(List<Object> found, Object argument) {
        if (found.isEmpty()) return Integer.MIN_VALUE;
        var value = found.get(0);
        if (value instanceof Number number)
            return Double.compare(number.doubleValue(), ((Number) argument).doubleValue());
        return ((Comparable<Object>) value).compareTo(argument);
    }

    private static boolean contains(Iterable<Object> options, Object value) {
        for (var option : options) if (same(option, value)) return true;
        return false;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        if (a instanceof String text && b instanceof java.util.regex.Pattern pattern)
            return pattern.matcher(text).find();
        return java.util.Objects.equals(a, b);
    }

    /** Values at a dotted path; arrays on the way are traversed and a final array also matches by element. */
    @SuppressWarnings("unchecked")
    private static List<Object> values(Object root, String path) {
        List<Object> current = List.of(root);
        for (var part : path.split("\\.")) {
            var next = new ArrayList<Object>();
            for (var item : current) {
                if (item instanceof Map<?, ?> map) {
                    if (map.containsKey(part)) next.add(map.get(part));
                } else if (item instanceof List<?> list)
                    for (var element : list)
                        if (element instanceof Map<?, ?> map && map.containsKey(part)) next.add(map.get(part));
            }
            current = next;
        }
        var flat = new ArrayList<Object>();
        for (var item : current) {
            flat.add(item);
            if (item instanceof List<?> list) flat.addAll(list);
        }
        return flat;
    }

    @SuppressWarnings("unchecked")
    static Document copy(Document source) {
        var result = new Document();
        source.forEach((key, value) -> result.put(key, copyValue(value)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Document document) return copy(document);
        if (value instanceof Map<?, ?> map) return copy(new Document((Map<String, Object>) map));
        if (value instanceof List<?> list)
            return new ArrayList<>(list.stream().map(InMemoryMongo::copyValue).toList());
        return value;
    }
}
