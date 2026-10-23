package com.openhire.aigateway.client.provider;

import com.openhire.aigateway.domain.AiTypes.ProviderException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

final class ProviderSupport {
    private ProviderSupport() {}

    static RestClient client(String baseUrl, Duration readTimeout, String bearer) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        var builder = RestClient.builder().baseUrl(trimmed(baseUrl)).requestFactory(factory);
        if (bearer != null && !bearer.isBlank()) builder.defaultHeader("Authorization", "Bearer " + bearer);
        return builder.build();
    }

    static String trimmed(String url) {
        var value = url.strip().replaceAll("/+$", "");
        return value.endsWith("/v1") ? value.substring(0, value.length() - 3) : value;
    }

    /** Loopback, host.docker.internal and *.local / *.localhost count as local; anything else is hosted. */
    static boolean isLocal(String url) {
        try {
            var host = URI.create(url.strip()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("localhost")
                    || host.equals("host.docker.internal")
                    || host.equals("::1")
                    || host.equals("[::1]")
                    || host.startsWith("127.")
                    || host.endsWith(".localhost")
                    || host.endsWith(".local");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Maps any client failure to a ProviderException; connection problems and 5xx count against the circuit. */
    static ProviderException failure(RuntimeException e) {
        if (e instanceof ProviderException p) return p;
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return new ProviderException("HTTP " + status, status >= 500 || status == 429, e);
        }
        return new ProviderException("Model server unreachable", true, e);
    }
}
