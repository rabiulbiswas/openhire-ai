package com.openhire.analytics.config;

import com.openhire.analytics.service.AnalyticsQueryService;
import com.openhire.analytics.service.DeadLetterService;
import com.openhire.analytics.service.HealthPollService;
import com.openhire.analytics.service.PlatformQueryService;
import com.openhire.analytics.service.PlatformSettings;
import com.openhire.analytics.service.ProjectionService;
import com.openhire.analytics.service.port.AnalyticsPorts.*;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
class AnalyticsConfiguration {
    @Bean
    com.openhire.analytics.service.MonitoredSlos monitoredSlos(
            PlatformQueryService platform, com.openhire.analytics.service.MonitoredSlos.Samples samples) {
        return new com.openhire.analytics.service.MonitoredSlos(platform, samples);
    }

    @Bean
    PlatformSettings platformSettings(
            @Value("${openhire.analytics.targets:}") String targets,
            @Value("${openhire.analytics.public-api-service:application-service}") String publicApiService,
            @Value("${openhire.analytics.window-hours:24}") long windowHours,
            @Value("${openhire.analytics.lag-threshold:100}") long lagThreshold,
            @Value("${openhire.analytics.slo.availability-pct:99.5}") double availabilityPct,
            @Value("${openhire.analytics.slo.latency-mean-ms:500}") double latencyMs,
            @Value("${openhire.analytics.slo.resume-pct:95}") double resumePct,
            @Value("${openhire.analytics.slo.resume-seconds:60}") long resumeSeconds,
            @Value("${openhire.analytics.slo.ai-pct:90}") double aiPct,
            @Value("${openhire.analytics.slo.ai-seconds:120}") long aiSeconds,
            @Value("${openhire.analytics.slo.notification-pct:99}") double notificationPct,
            @Value("${openhire.analytics.slo.notification-seconds:60}") long notificationSeconds,
            @Value("${openhire.analytics.dlq-replay-per-minute:20}") int replayPerMinute,
            @Value("${openhire.analytics.dlq-peek-limit:200}") int peekLimit) {
        return new PlatformSettings(
                parseTargets(targets),
                publicApiService,
                Duration.ofHours(windowHours),
                lagThreshold,
                availabilityPct,
                latencyMs,
                resumePct,
                Duration.ofSeconds(resumeSeconds),
                aiPct,
                Duration.ofSeconds(aiSeconds),
                notificationPct,
                Duration.ofSeconds(notificationSeconds),
                replayPerMinute,
                peekLimit);
    }

    /** {@code name=url,name=url}, keeping the configured order. */
    static Map<String, String> parseTargets(String csv) {
        var result = new LinkedHashMap<String, String>();
        for (var pair : csv.split(",")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            var url = pair.substring(split + 1).trim();
            result.put(pair.substring(0, split).trim(), url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        }
        return result;
    }

    @Bean
    ProjectionService projectionService(
            ProcessedEvents processed,
            ApplicationFacts applications,
            AssessmentFacts assessments,
            DecisionFacts decisions,
            OfferFacts offers,
            AiDayFacts aiDays,
            LatencyFacts latencies,
            JobTitles titles,
            ApplicationDirectory directory,
            Clock clock) {
        return new ProjectionService(
                processed, applications, assessments, decisions, offers, aiDays, latencies, titles, directory, clock);
    }

    @Bean
    AnalyticsQueryService analyticsQueryService(
            ApplicationFacts applications,
            AssessmentFacts assessments,
            DecisionFacts decisions,
            OfferFacts offers,
            AiDayFacts aiDays,
            JobTitles titles,
            Clock clock) {
        return new AnalyticsQueryService(applications, assessments, decisions, offers, aiDays, titles, clock);
    }

    @Bean
    HealthPollService healthPollService(
            PlatformSettings settings, ActuatorClient actuator, HealthStore store, Clock clock) {
        return new HealthPollService(settings, actuator, store, clock);
    }

    @Bean
    PlatformQueryService platformQueryService(
            PlatformSettings settings,
            HealthStore health,
            LatencyFacts latencies,
            ConsumerOffsets offsets,
            Clock clock) {
        return new PlatformQueryService(settings, health, latencies, offsets, clock);
    }

    @Bean
    DeadLetterService deadLetterService(
            DeadLetterSource source,
            Republisher republisher,
            ReplayLog replays,
            AuditTrail audit,
            ObjectMapper json,
            Clock clock,
            PlatformSettings settings) {
        return new DeadLetterService(
                source,
                republisher,
                replays,
                audit,
                json,
                clock,
                settings.replayPerMinute(),
                settings.peekPerPartition());
    }
}
