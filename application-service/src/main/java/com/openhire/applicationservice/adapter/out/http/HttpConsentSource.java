package com.openhire.applicationservice.adapter.out.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openhire.applicationservice.application.port.ConsentSource;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads the active consent text from compliance-service. */
@Component
public class HttpConsentSource implements ConsentSource {
    private static final Logger LOG = LoggerFactory.getLogger(HttpConsentSource.class);
    private final RestClient http;

    public HttpConsentSource(
            @Value("${openhire.compliance-url:http://localhost:8095}") String url,
            @Value("${openhire.internal-secret}") String secret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(2500);
        http = RestClient.builder()
                .baseUrl(url)
                .defaultHeader("X-Internal-Service-Secret", secret)
                .requestFactory(factory)
                .build();
    }

    @Override
    public Optional<Consent> active(String tenantId) {
        try {
            var body = http.get()
                    .uri("/api/v1/internal/consent/active?tenantId={tenant}&purpose=PROCESSING", tenantId)
                    .retrieve()
                    .body(ActiveConsent.class);
            if (body == null || blank(body.version()) || blank(body.text())) return Optional.empty();
            return Optional.of(new Consent(body.version(), body.text()));
        } catch (RuntimeException failure) {
            LOG.warn("Active consent text unavailable, using the built-in text: {}", failure.toString());
            return Optional.empty();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActiveConsent(String version, String text) {}
}
