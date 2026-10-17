package com.openhire.talent.config;

import com.openhire.talent.application.TalentSettings;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TalentConfiguration {
    @Bean
    TalentSettings talentSettings(
            @Value("${openhire.talent.retention-months:24}") int retentionMonths,
            @Value("${openhire.talent.rediscovery-top:500}") int rediscoveryTop,
            @Value("${openhire.talent.explain-top:10}") int explainTop,
            @Value("${openhire.talent.result-limit:50}") int resultLimit,
            @Value("${openhire.talent.cooldown-days:30}") int cooldownDays,
            @Value("${openhire.talent.internal-mobility:false}") boolean internalMobility,
            @Value("${openhire.talent.expiry-warning-days:60}") int expiryWarningDays,
            @Value("${openhire.talent.run-cache-minutes:10}") int runCacheMinutes) {
        return new TalentSettings(
                retentionMonths,
                rediscoveryTop,
                explainTop,
                resultLimit,
                Duration.ofDays(cooldownDays),
                internalMobility,
                Duration.ofDays(expiryWarningDays),
                Duration.ofMinutes(runCacheMinutes));
    }
}
