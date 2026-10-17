package com.openhire.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class WorkflowKafkaErrors {
    @Bean
    CommonErrorHandler workflowKafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        var deadLetter = new DeadLetterPublishingRecoverer(kafka);
        deadLetter.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler(deadLetter, new FixedBackOff(1000, 3));
        handler.setBackOffFunction((record, failure) -> {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.springframework.dao.DataAccessException
                        || cause instanceof org.springframework.web.client.ResourceAccessException)
                    return new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS);
                if (cause instanceof org.springframework.web.client.RestClientResponseException response
                        && response.getStatusCode().is5xxServerError())
                    return new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS);
            }
            return new FixedBackOff(1000, 3);
        });
        return handler;
    }
}
