package com.openhire.identity.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentityConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
