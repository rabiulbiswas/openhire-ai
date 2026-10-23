package com.openhire.dispatch.scheduler;

import com.openhire.dispatch.service.DispatchService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class DeferredDeliveryScheduler {
    private final DispatchService dispatch;
    private final StringRedisTemplate redis;
    private final String owner = UUID.randomUUID().toString();

    DeferredDeliveryScheduler(DispatchService dispatch, StringRedisTemplate redis) {
        this.dispatch = dispatch;
        this.redis = redis;
    }

    /** Sends deliveries whose quiet hours, throttle window or retry back-off has ended. */
    @Scheduled(fixedDelayString = "${openhire.dispatch.release-delay:30000}", initialDelay = 20000)
    void releaseDeferred() {
        if (Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent("lock:dispatch-release", owner, Duration.ofSeconds(25))))
            dispatch.releaseDue();
    }

    /** Failed message content is kept for operator retries, then removed. */
    @Scheduled(fixedDelayString = "${openhire.dispatch.purge-delay:3600000}", initialDelay = 60000)
    void purge() {
        dispatch.purgeExpiredContent();
    }
}
