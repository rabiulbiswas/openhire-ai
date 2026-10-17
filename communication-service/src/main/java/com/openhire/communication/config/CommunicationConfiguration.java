package com.openhire.communication.config;

import com.openhire.communication.application.CommunicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CommunicationConfiguration {
    @Bean
    CommunicationService.Settings communicationSettings(
            @Value("${openhire.communication.company-name:Northwind Payments}") String companyName,
            @Value("${openhire.communication.default-timezone:Asia/Kolkata}") String timezone) {
        java.time.ZoneId.of(timezone);
        return new CommunicationService.Settings(companyName, timezone);
    }
}
