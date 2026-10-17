package com.openhire.integration.config;

import com.openhire.integration.application.IntegrationService;
import com.openhire.platform.audit.AuditRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IntegrationConfiguration {
    @Bean
    com.openhire.integration.application.CalendarConnections calendarConnections(
            IntegrationService.Store configs,
            com.openhire.integration.application.CalendarPorts.Store store,
            com.openhire.integration.application.CalendarPorts.Secrets secrets,
            com.openhire.integration.application.CalendarPorts.Provider provider,
            java.time.Clock clock,
            AuditRecorder audit) {
        return new com.openhire.integration.application.CalendarConnections(
                configs, store, secrets, provider, clock, audit);
    }

    @Bean
    IntegrationService integrationService(IntegrationService.Store store, AuditRecorder audit) {
        return new IntegrationService(store, audit);
    }
}
