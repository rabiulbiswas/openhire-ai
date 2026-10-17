package com.openhire.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfiguration {
    @Bean
    org.springframework.cloud.gateway.config.HttpClientCustomizer dockerDnsRefresh() {
        return client -> client.resolver(resolver -> resolver.cacheMinTimeToLive(java.time.Duration.ZERO)
                .cacheMaxTimeToLive(java.time.Duration.ofSeconds(10))
                .cacheNegativeTimeToLive(java.time.Duration.ZERO));
    }

    @Bean
    KeyResolver publicIntakeKeyResolver() {
        return exchange -> {
            var remote = exchange.getRequest().getRemoteAddress();
            return Mono.just("intake:" + (remote == null ? "unknown" : remote.getHostString()));
        };
    }

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @org.springframework.context.annotation.Primary
    KeyResolver tenantKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst("X-Tenant-Id"))
                .switchIfEmpty(Mono.just("public:" + exchange.getRequest().getRemoteAddress()));
    }
}
