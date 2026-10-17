package com.openhire.jobservice.api;

import java.util.List;

public record PageResponse<T>(List<T> data, String nextCursor, boolean hasMore) {
    public static <T> PageResponse<T> singlePage(List<T> data) {
        return new PageResponse<>(List.copyOf(data), null, false);
    }
}
