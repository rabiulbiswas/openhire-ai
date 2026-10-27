package com.openhire.applicationservice.dto;

import java.util.List;
import java.util.function.Function;

public record PartnerPage<T>(
        List<T> data, String nextCursor, boolean hasMore, String previousCursor, boolean hasPrevious, int limit) {
    public <R> PartnerPage<R> map(Function<T, R> mapper) {
        return new PartnerPage<>(
                data.stream().map(mapper).toList(), nextCursor, hasMore, previousCursor, hasPrevious, limit);
    }
}
