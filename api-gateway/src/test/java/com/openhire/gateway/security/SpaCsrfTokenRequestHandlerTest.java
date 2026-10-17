package com.openhire.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;

class SpaCsrfTokenRequestHandlerTest {
    @Test
    void resolvesTheRawCookieTokenSentByTheSpaHeader() {
        var token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-cookie-value");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/applications").header("X-XSRF-TOKEN", "raw-cookie-value"));
        assertThat(new SpaCsrfTokenRequestHandler()
                        .resolveCsrfTokenValue(exchange, token)
                        .block())
                .isEqualTo(token.getToken());
    }
}
