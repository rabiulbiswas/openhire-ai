package com.openhire.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class GatewayConfigurationTest {
    @Test
    void defaultRateLimiterCanResolveOnePrimaryKeyResolver() {
        try (var context = new AnnotationConfigApplicationContext(GatewayConfiguration.class)) {
            assertThat(context.getBean(KeyResolver.class)).isSameAs(context.getBean("tenantKeyResolver"));
            assertThat(context.getBean("publicIntakeKeyResolver")).isInstanceOf(KeyResolver.class);
        }
    }
}
