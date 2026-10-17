package com.openhire.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ApiKeyAuthenticationFilter implements GlobalFilter, Ordered {
    private static final String HEADER = "X-API-Key";
    private final ReactiveStringRedisTemplate redis;
    private final WebClient identity;
    private final String internalSecret;

    public ApiKeyAuthenticationFilter(
            ReactiveStringRedisTemplate redis,
            WebClient.Builder webClient,
            @Value("${openhire.identity-service-url}") String identityServiceUrl,
            @Value("${openhire.internal-secret}") String internalSecret) {
        this.redis = redis;
        this.identity = webClient.baseUrl(identityServiceUrl).build();
        this.internalSecret = internalSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // This write endpoint validates the original key and its scope inside application-service.
        // Do not replace its credentials with unsigned forwarded tenant/scope headers.
        if (exchange.getRequest().getPath().value().equals("/api/v1/public/api-applications"))
            return chain.filter(exchange);
        var presented = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (presented == null || presented.isBlank()) return chain.filter(exchange);

        var cacheKey = "openhire:api-key:" + sha256(presented);
        return redis.opsForValue()
                .get(cacheKey)
                .map(this::decode)
                .switchIfEmpty(validate(presented).flatMap(value -> redis.opsForValue()
                        .set(cacheKey, encode(value), Duration.ofMinutes(5))
                        .thenReturn(value)))
                .flatMap(value -> chain.filter(exchange.mutate()
                        .request(request -> request.headers(headers -> {
                            headers.remove(HEADER);
                            headers.set("X-Tenant-Id", value.organizationId());
                            headers.set("X-Api-Key-Scopes", String.join(" ", value.scopes()));
                        }))
                        .build()))
                .onErrorResume(error -> {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }

    private Mono<ValidatedApiKey> validate(String presented) {
        return identity.post()
                .uri("/api/v1/internal/api-keys/validate")
                .header("X-Internal-Secret", internalSecret)
                .bodyValue(new ApiKeyRequest(presented))
                .retrieve()
                .bodyToMono(ValidatedApiKey.class);
    }

    private String encode(ValidatedApiKey value) {
        return value.organizationId() + "|" + String.join(",", value.scopes());
    }

    private ValidatedApiKey decode(String value) {
        var parts = value.split("\\|", 2);
        var scopes = parts.length == 1 || parts[1].isBlank() ? Set.<String>of() : Set.of(parts[1].split(","));
        return new ValidatedApiKey(parts[0], scopes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private record ApiKeyRequest(String apiKey) {}

    private record ValidatedApiKey(String organizationId, Set<String> scopes) {}
}
