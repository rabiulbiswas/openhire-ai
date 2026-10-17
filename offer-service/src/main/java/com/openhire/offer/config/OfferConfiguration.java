package com.openhire.offer.config;

import com.openhire.offer.application.OfferSettings;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OfferConfiguration {
    @Bean
    OfferSettings offerSettings(
            @Value("${openhire.offer.default-expiry-days:7}") int expiryDays,
            @Value("${openhire.offer.portal-url:http://localhost:3000}") String portalUrl,
            @Value("${openhire.offer.company-name:Northwind Payments}") String companyName,
            @Value("${openhire.offer.default-timezone:Asia/Kolkata}") String timezone,
            @Value("${openhire.offer.token-grace-days:30}") long graceDays) {
        return new OfferSettings(
                expiryDays,
                Duration.ofDays(graceDays),
                portalUrl.replaceAll("/+$", ""),
                companyName,
                ZoneId.of(timezone));
    }
}
