package com.openhire.talent.repository.redis;

import com.openhire.talent.service.port.TalentPorts.SweepLock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Lease lock (lock:{name}) so only one instance runs a scheduled job at a time. */
@Component
class RedisSweepLock implements SweepLock {
    private final StringRedisTemplate redis;
    private final String owner = UUID.randomUUID().toString();

    RedisSweepLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String name, Duration lease) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("lock:" + name, owner, lease));
    }
}
