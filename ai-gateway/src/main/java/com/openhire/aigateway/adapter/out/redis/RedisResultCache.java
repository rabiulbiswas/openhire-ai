package com.openhire.aigateway.adapter.out.redis;

import com.openhire.aigateway.application.port.AiPorts.ResultCache;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Content-addressed result cache. Redis trouble is a cache miss, never a failed AI call. */
@Component
class RedisResultCache implements ResultCache {
    private static final Logger log = LoggerFactory.getLogger(RedisResultCache.class);
    private final StringRedisTemplate redis;

    RedisResultCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException e) {
            log.debug("Cache read skipped ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.debug("Cache write skipped ({})", e.getClass().getSimpleName());
        }
    }
}
