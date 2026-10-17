package com.openhire.aiworkers.matching.adapter.in.schedule;

import com.openhire.aiworkers.matching.application.MatchingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class MatchingRetryScheduler {
    private static final int PER_TICK = 5;
    private final MatchingService matching;

    MatchingRetryScheduler(MatchingService matching) {
        this.matching = matching;
    }

    @Scheduled(fixedDelayString = "${openhire.ai.sweep-delay:30000}", initialDelay = 10000)
    void retry() {
        matching.runDueRetries(PER_TICK);
    }
}
