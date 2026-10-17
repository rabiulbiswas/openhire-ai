package com.openhire.dispatch.adapter.out.redis;

import com.openhire.dispatch.application.port.DispatchPorts.SendLock;
import com.openhire.dispatch.application.port.DispatchPorts.Throttle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisDispatchGuards implements Throttle, SendLock {
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String owner = UUID.randomUUID().toString();

    RedisDispatchGuards(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Fixed one-hour window counter: rl:dispatch:{tenant}:{hashed recipient}:{hour}. Recipients are hashed, not stored. */
    @Override
    public boolean allow(String tenantId, String recipientKey, int perHour) {
        long hour = clock.instant().getEpochSecond() / 3600;
        var key = "rl:dispatch:" + tenantId + ":" + hash(recipientKey) + ":" + hour;
        var count = redis.opsForValue().increment(key);
        if (count != null && count == 1) redis.expire(key, Duration.ofHours(2));
        return count != null && count <= perHour;
    }

    @Override
    public boolean acquire(String messageId, Duration lease) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("lock:dispatch:" + messageId, owner, lease));
    }

    @Override
    public void release(String messageId) {
        var key = "lock:dispatch:" + messageId;
        if (owner.equals(redis.opsForValue().get(key))) redis.delete(key);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
