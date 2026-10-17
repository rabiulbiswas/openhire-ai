package com.openhire.aiworkers.matching.application;

import java.time.Duration;
import java.util.List;

public record MatchingSettings(int concurrency, List<Duration> retryDelays, Duration retryWindow, Duration lease) {
    /** Back-off after the n-th failure: the configured steps, then daily until the window closes. */
    public Duration delay(int failures) {
        return failures < retryDelays.size() ? retryDelays.get(failures) : Duration.ofDays(1);
    }
}
