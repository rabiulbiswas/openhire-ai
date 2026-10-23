package com.openhire.integration.config;

import com.openhire.integration.service.IntegrationService;
import com.openhire.platform.audit.AuditRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IntegrationConfiguration {
    @Bean
    com.openhire.integration.service.CalendarConnections calendarConnections(
            IntegrationService.Store configs,
            com.openhire.integration.service.CalendarPorts.Store store,
            com.openhire.integration.service.CalendarPorts.Secrets secrets,
            com.openhire.integration.service.CalendarPorts.Provider provider,
            java.time.Clock clock,
            AuditRecorder audit) {
        return new com.openhire.integration.service.CalendarConnections(
                configs, store, secrets, provider, clock, audit);
    }

    @Bean
    IntegrationService integrationService(IntegrationService.Store store, AuditRecorder audit) {
        return new IntegrationService(store, audit);
    }
}
