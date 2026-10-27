package com.openhire.gateway.security;

import java.util.Map;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Business query parameters must never change the scopes granted during login. */
@Configuration(proxyBeanMethods = false)
public class TokenRelayRefreshConfiguration {
    @Bean
    static BeanPostProcessor relayContextAttributes() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DefaultReactiveOAuth2AuthorizedClientManager manager) {
                    manager.setContextAttributesMapper(request -> Mono.deferContextual(context -> {
                        ServerWebExchange exchange = request.getAttribute(ServerWebExchange.class.getName());
                        if (exchange == null) exchange = context.getOrDefault(ServerWebExchange.class, null);
                        // Preserve the exchange for OIDC/session updates, but do not import
                        // the API's ?scope=waiting_on_me (or any other caller-supplied scope).
                        return Mono.just(
                                exchange == null
                                        ? Map.of()
                                        : Map.<String, Object>of(ServerWebExchange.class.getName(), exchange));
                    }));
                }
                return bean;
            }
        };
    }
}
