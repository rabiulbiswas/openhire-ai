package com.openhire.dispatch.config;

import com.openhire.dispatch.application.DispatchService;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DispatchConfiguration {
    @Bean
    DispatchService.Settings dispatchSettings(
            @Value("${openhire.dispatch.notifications-per-hour:30}") int perHour,
            @Value("${openhire.dispatch.throttle-delay-minutes:15}") long throttleDelay,
            @Value("${openhire.dispatch.max-scheduled-attempts:4}") int maxAttempts,
            @Value("${openhire.dispatch.failed-content-retention-days:7}") long retentionDays) {
        return new DispatchService.Settings(
                perHour, Duration.ofMinutes(throttleDelay), maxAttempts, Duration.ofDays(retentionDays));
    }
}
