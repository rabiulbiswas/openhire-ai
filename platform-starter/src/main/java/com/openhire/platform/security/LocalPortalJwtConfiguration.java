package com.openhire.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "openhire.portals.same-host-auth", havingValue = "true")
public class LocalPortalJwtConfiguration {
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder localPortalJwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), new LocalPortalIssuerValidator()));
        return decoder;
    }
}
