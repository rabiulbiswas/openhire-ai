package com.openhire.aigateway.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small JSON Schema subset (type, required, properties, items, enum, minimum, maximum, minItems,
 * maxItems, minLength, maxLength) over parsed JSON. It is enough for the prompt output schemas and avoids a
 * dependency that still targets the previous Jackson generation.
 */
public final class SchemaValidator {
    private SchemaValidator() {}

    public static List<String> validate(Map<String, Object> schema, Object value) {
        var errors = new ArrayList<String>();
        check(schema, value, "$", errors);
        return errors;
    }

    private static void check(Map<String, Object> schema, Object value, String path, List<String> errors) {
        if (schema == null || schema.isEmpty() || errors.size() >= 12) return;
        var type = schema.get("type");
        if (type != null && !typeMatches(type, value)) {
            errors.add(path + " must be " + type + (value == null ? " but is missing" : ""));
            return;
        }
        if (schema.get("enum") instanceof List<?> allowed && value != null && !allowed.contains(value))
            errors.add(path + " must be one of " + allowed);
        if (value instanceof Number number) {
            if (schema.get("minimum") instanceof Number min && number.doubleValue() < min.doubleValue())
                errors.add(path + " must be at least " + min);
            if (schema.get("maximum") instanceof Number max && number.doubleValue() > max.doubleValue())
                errors.add(path + " must be at most " + max);
        }
        if (value instanceof String text) {
            if (schema.get("minLength") instanceof Number min && text.length() < min.intValue())
                errors.add(path + " is too short");
            if (schema.get("maxLength") instanceof Number max && text.length() > max.intValue())
                errors.add(path + " is too long");
        }
        if (value instanceof Map<?, ?> object) {
            if (schema.get("required") instanceof List<?> required)
                for (var key : required) if (object.get(key) == null) errors.add(path + "." + key + " is required");
            if (schema.get("properties") instanceof Map<?, ?> properties)
                for (var entry : properties.entrySet())
                    if (object.get(entry.getKey()) != null)
                        check(
                                Json.map(entry.getValue()),
                                object.get(entry.getKey()),
                                path + "." + entry.getKey(),
                                errors);
        }
        if (value instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.intValue())
                errors.add(path + " needs at least " + min + " items");
            if (schema.get("maxItems") instanceof Number max && list.size() > max.intValue())
                errors.add(path + " allows at most " + max + " items");
            if (schema.get("items") instanceof Map<?, ?> items)
                for (int i = 0; i < list.size(); i++) check(Json.map(items), list.get(i), path + "[" + i + "]", errors);
        }
    }

    private static boolean typeMatches(Object type, Object value) {
        if (type instanceof List<?> options) return options.stream().anyMatch(t -> typeMatches(t, value));
        return switch (type.toString()) {
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" ->
                value instanceof Integer
                        || value instanceof Long
                        || (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()));
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }
}
