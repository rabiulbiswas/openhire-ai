package com.openhire.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.ReactiveSessionRepositoryCustomizer;
import org.springframework.session.data.redis.ReactiveRedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisSessionMapper;
import reactor.core.publisher.Mono;

/** Treat incomplete Redis session snapshots as missing sessions, never as authenticated users. */
@Configuration(proxyBeanMethods = false)
public class RedisSessionRecoveryConfiguration {

    @Bean
    ReactiveSessionRepositoryCustomizer<ReactiveRedisIndexedSessionRepository> incompleteSessionRecovery() {
        var delegate = new RedisSessionMapper();
        return repository -> repository.setRedisSessionMapper((id, attributes) -> Mono.defer(() -> {
            // An expiry/delete racing with a delta save can leave only session attributes.
            // Do not manufacture timestamps: that could revive an expired authentication.
            if (attributes.get("creationTime") == null
                    || attributes.get("lastAccessedTime") == null
                    || attributes.get("maxInactiveInterval") == null) {
                return Mono.empty();
            }
            // Keep normal mapping and failures for other corruption/Redis availability errors.
            // Do not call repository.deleteById here: indexed deletion invokes this mapper
            // again. Leave Redis expiry/retention to clean up the invalid snapshot, avoiding
            // deletion of a concurrently repaired hash or unrelated sessions.
            return Mono.just(delegate.apply(id, attributes));
        }));
    }
}
