package com.openhire.jobservice.adapter.out.redis;

import com.openhire.jobservice.application.port.IdempotencyStore;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisIdempotencyStore implements IdempotencyStore {
    private final StringRedisTemplate redis;

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> findResourceId(String tenantId, String key) {
        return Optional.ofNullable(redis.opsForValue().get(redisKey(tenantId, key)));
    }

    @Override
    public void remember(String tenantId, String key, String resourceId, Duration ttl) {
        redis.opsForValue().set(redisKey(tenantId, key), resourceId, ttl);
    }

    private String redisKey(String tenantId, String key) {
        return "idem:" + tenantId + ":" + key;
    }
}
