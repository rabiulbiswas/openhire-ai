package com.openhire.communication.adapter.in.schedule;

import com.openhire.communication.application.CommunicationService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends hourly and daily digests; a Redis lease keeps it to one instance per tick. */
@Component
class DigestScheduler {
    private final CommunicationService communication;
    private final StringRedisTemplate redis;
    private final String owner = UUID.randomUUID().toString();

    DigestScheduler(CommunicationService communication, StringRedisTemplate redis) {
        this.communication = communication;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${openhire.communication.digest-delay:300000}", initialDelay = 30000)
    void sendDigests() {
        if (Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent("lock:communication-digest", owner, Duration.ofMinutes(4))))
            communication.sendDueDigests();
    }
}
