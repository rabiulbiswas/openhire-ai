package com.openhire.support;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class WorkflowSecurity {
    @Bean
    Clock workflowClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain workflowFilterChain(HttpSecurity http, @Value("${openhire.security.mode:oidc}") String mode)
            throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        if ("local".equals(mode))
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                    .anonymous(a -> a.authorities("ROLE_RECRUITER"))
                    .build();
        if (!"oidc".equals(mode)) throw new IllegalArgumentException("Unknown security mode");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var result = new ArrayList<GrantedAuthority>();
            if (jwt.getClaim("realm_access") instanceof Map<?, ?> realm
                    && realm.get("roles") instanceof Collection<?> roles)
                roles.forEach(role -> result.add(
                        new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase(Locale.ROOT))));
            return result;
        });
        return http.authorizeHttpRequests(a -> a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/actuator/health/**", "/api/v1/internal/**", "/api/v1/public/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(r -> r.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }
}
