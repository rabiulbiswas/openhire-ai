package com.openhire.analytics.scheduler;

import com.openhire.analytics.service.HealthPollService;
import com.openhire.analytics.service.port.AnalyticsPorts.SweepLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class HealthScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(HealthScheduler.class);

    private final HealthPollService polls;
    private final SweepLock lock;
    private final Duration lease;

    HealthScheduler(
            HealthPollService polls, SweepLock lock, @Value("${openhire.analytics.poll-seconds:15}") long pollSeconds) {
        this.polls = polls;
        this.lock = lock;
        this.lease = Duration.ofSeconds(Math.max(1, pollSeconds - 1));
    }

    /** One instance per interval; the lease is a little shorter than the interval so the next tick can take it. */
    @Scheduled(
            fixedDelayString = "#{${openhire.analytics.poll-seconds:15} * 1000}",
            initialDelayString = "#{${openhire.analytics.poll-seconds:15} * 1000}")
    void poll() {
        try {
            if (lock.tryAcquire("analytics-health-poll", lease)) polls.pollAll();
        } catch (RuntimeException failure) {
            LOG.warn("Health poll round failed: {}", failure.toString());
        }
    }
}
