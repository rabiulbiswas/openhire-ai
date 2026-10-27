package com.openhire.gateway.security;

import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Private identity calls use only the server-verified principal, never submitted actor/tenant IDs. */
@Component
public class MemberProfileClient {
    private final WebClient identity;

    public MemberProfileClient(
            @Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String url,
            @Value("${INTERNAL_SERVICE_SECRET:local-development-secret}") String secret) {
        identity = WebClient.builder()
                .baseUrl(url)
                .defaultHeader("X-Internal-Service-Secret", secret)
                .build();
    }

    public Mono<String> name(Principal principal) {
        var user = user(principal);
        if (user == null) return Mono.empty();
        return identity.get()
                .uri(uri -> uri.path("/api/v1/internal/member-profile")
                        .queryParam("tenantId", user.get("tenantId"))
                        .queryParam("subject", user.get("subject"))
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> body.get("name").toString())
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(WebClientResponseException.NotFound.class, error -> Mono.empty());
    }

    public Mono<Void> update(Principal principal, String name) {
        var user = user(principal);
        if (user == null)
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "A tenant member account is required"));
        return identity.patch()
                .uri("/api/v1/internal/member-profile")
                .bodyValue(Map.of("tenantId", user.get("tenantId"), "subject", user.get("subject"), "name", name))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .then();
    }

    private Map<String, String> user(Principal principal) {
        if (!(principal instanceof OAuth2AuthenticationToken oauth)) return null;
        var attributes = oauth.getPrincipal().getAttributes();
        var tenant = TenantClaims.resolve(attributes);
        var subject = attributes.get("sub");
        if (tenant == null
                || tenant.isBlank()
                || subject == null
                || subject.toString().isBlank()) return null;
        return Map.of("tenantId", tenant, "subject", subject.toString());
    }
}
