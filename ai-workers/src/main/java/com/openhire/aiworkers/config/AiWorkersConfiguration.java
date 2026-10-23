package com.openhire.aiworkers.config;

import com.openhire.aiworkers.integrity.domain.AiTextLikelihoodDetector;
import com.openhire.aiworkers.integrity.domain.Detector;
import com.openhire.aiworkers.integrity.domain.InjectionAttemptDetector;
import com.openhire.aiworkers.integrity.domain.TimelineOverlapDetector;
import com.openhire.aiworkers.integrity.service.DuplicateIdentityDetector;
import com.openhire.aiworkers.integrity.service.IntegrityService;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.ApplicationDirectory;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.IdentityIndex;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.SignalStore;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.Structuring;
import com.openhire.aiworkers.matching.domain.Lexical;
import com.openhire.aiworkers.matching.domain.Taxonomy;
import com.openhire.aiworkers.matching.service.MatchingService;
import com.openhire.aiworkers.matching.service.MatchingSettings;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.AiGateway;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.AssessmentStore;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.ChunkStore;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.CriterionVectors;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.RetryStore;
import com.openhire.aiworkers.shared.service.port.ResumeSource;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.EmbeddedEventRelay;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
class AiWorkersConfiguration {
    @Bean
    MatchingSettings matchingSettings(
            @Value("${openhire.ai.concurrency:1}") int concurrency,
            @Value("${openhire.ai.retry-delays-seconds:60,300,1800}") String retryDelays,
            @Value("${openhire.ai.retry-window-hours:72}") long retryWindowHours,
            @Value("${openhire.ai.retry-lease-seconds:600}") long leaseSeconds) {
        return new MatchingSettings(
                concurrency,
                Arrays.stream(retryDelays.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                        .toList(),
                Duration.ofHours(retryWindowHours),
                Duration.ofSeconds(leaseSeconds));
    }

    @Bean
    MatchingService matchingService(
            AiGateway gateway,
            ResumeSource resumes,
            ChunkStore chunks,
            CriterionVectors vectors,
            AssessmentStore assessments,
            RetryStore retries,
            MatchingSettings settings,
            Clock clock) {
        return new MatchingService(
                gateway,
                resumes,
                chunks,
                vectors,
                assessments,
                retries,
                new Lexical(Taxonomy.builtIn()),
                settings,
                clock);
    }

    @Bean
    Detector timelineOverlapDetector(Clock clock) {
        return new TimelineOverlapDetector(clock);
    }

    @Bean
    Detector duplicateIdentityDetector(IdentityIndex index) {
        return new DuplicateIdentityDetector(index);
    }

    @Bean
    Detector injectionAttemptDetector() {
        return new InjectionAttemptDetector();
    }

    @Bean
    Detector aiTextLikelihoodDetector(@Value("${openhire.ai.ai-text-detector:false}") boolean enabled) {
        return new AiTextLikelihoodDetector(enabled);
    }

    @Bean
    IntegrityService integrityService(
            List<Detector> detectors,
            ResumeSource resumes,
            ApplicationDirectory applications,
            Structuring structuring,
            IdentityIndex identities,
            SignalStore signals,
            Clock clock,
            AuditRecorder audit) {
        return new IntegrityService(detectors, resumes, applications, structuring, identities, signals, clock, audit);
    }

    /**
     * The shared relay publishes one collection to one topic ({@code assessments} to {@code assessment.events.v1}, from
     * the outbox properties). Integrity signals need their own topic, so a second instance covers that collection.
     */
    @Bean
    @ConditionalOnProperty(name = "openhire.outbox.relay-enabled")
    EmbeddedEventRelay integrityEventRelay(
            MongoTemplate mongo, KafkaTemplate<String, String> kafka, ObjectMapper json) {
        return new EmbeddedEventRelay(mongo, kafka, json, "integrity_signals", "integrity.events.v1");
    }
}
