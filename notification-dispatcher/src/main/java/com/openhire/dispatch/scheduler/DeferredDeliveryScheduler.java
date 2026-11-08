package com.openhire.dispatch.scheduler;

import com.openhire.dispatch.service.DispatchService;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class DeferredDeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(DeferredDeliveryScheduler.class);
    private final DispatchService dispatch;
    private final StringRedisTemplate redis;
    private final String owner = UUID.randomUUID().toString();
    private boolean releaseStoreUnavailable;

    DeferredDeliveryScheduler(DispatchService dispatch, StringRedisTemplate redis) {
        this.dispatch = dispatch;
        this.redis = redis;
    }

    /** Sends deliveries whose quiet hours, throttle window or retry back-off has ended. */
    @Scheduled(fixedDelayString = "${openhire.dispatch.release-delay:30000}", initialDelay = 20000)
    void releaseDeferred() {
        try {
            // Never send without the shared lock when Redis is unavailable.
            if (Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent("lock:dispatch-release", owner, Duration.ofSeconds(25))))
                dispatch.releaseDue();
            if (releaseStoreUnavailable) log.info("Deferred delivery storage recovered; scheduled retries resumed");
            releaseStoreUnavailable = false;
        } catch (DataAccessException unavailable) {
            if (!releaseStoreUnavailable)
                log.warn(
                        "Deferred delivery release paused ({}); pending deliveries will retry on the next scheduled run",
                        unavailable.getClass().getSimpleName());
            releaseStoreUnavailable = true;
        }
    }

    /** Failed message content is kept for operator retries, then removed. */
    @Scheduled(fixedDelayString = "${openhire.dispatch.purge-delay:3600000}", initialDelay = 60000)
    void purge() {
        dispatch.purgeExpiredContent();
    }
}
