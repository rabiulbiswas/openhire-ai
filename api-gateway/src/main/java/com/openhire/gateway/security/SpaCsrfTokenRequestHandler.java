package com.openhire.gateway.security;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Cookie-based SPA headers carry the raw token; rendered form attributes remain XOR masked. */
final class SpaCsrfTokenRequestHandler extends ServerCsrfTokenRequestAttributeHandler {
    private final XorServerCsrfTokenRequestAttributeHandler masked = new XorServerCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(ServerWebExchange exchange, Mono<CsrfToken> csrfToken) {
        masked.handle(exchange, csrfToken);
    }

    @Override
    public Mono<String> resolveCsrfTokenValue(ServerWebExchange exchange, CsrfToken csrfToken) {
        return StringUtils.hasText(exchange.getRequest().getHeaders().getFirst(csrfToken.getHeaderName()))
                ? super.resolveCsrfTokenValue(exchange, csrfToken)
                : masked.resolveCsrfTokenValue(exchange, csrfToken);
    }
}
