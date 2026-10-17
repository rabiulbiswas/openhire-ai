package com.openhire.analytics.adapter.out.http;

import com.openhire.analytics.application.port.AnalyticsPorts.ActuatorClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Plain HTTP so a 503 from a DOWN service still yields its body; any failure to connect is a result, not an error. */
@Component
class HttpActuatorClient implements ActuatorClient {
    private static final Logger LOG = LoggerFactory.getLogger(HttpActuatorClient.class);
    private static final String METRIC = "/actuator/metrics/http.server.requests";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper json;

    HttpActuatorClient(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Health health(String baseUrl) {
        long started = System.nanoTime();
        try {
            var response = get(baseUrl + "/actuator/health");
            long took = (System.nanoTime() - started) / 1_000_000;
            var status = response.statusCode() == 404
                    ? "UNKNOWN"
                    : String.valueOf(parse(response.body()).get("status"));
            return new Health(true, "null".equals(status) ? "UNKNOWN" : status, took);
        } catch (java.io.IOException | RuntimeException failure) {
            return new Health(false, "UNREACHABLE", (System.nanoTime() - started) / 1_000_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Health(false, "UNREACHABLE", (System.nanoTime() - started) / 1_000_000);
        }
    }

    @Override
    public Optional<HttpMetrics> httpMetrics(String baseUrl, boolean serverErrorsOnly) {
        try {
            var response = get(baseUrl + METRIC + (serverErrorsOnly ? "?tag=outcome:SERVER_ERROR" : ""));
            if (response.statusCode() != 200) return Optional.empty();
            double count = 0;
            double total = 0;
            double max = 0;
            if (parse(response.body()).get("measurements") instanceof List<?> measurements)
                for (var item : measurements) {
                    if (!(item instanceof Map<?, ?> m) || !(m.get("value") instanceof Number value)) continue;
                    switch (String.valueOf(m.get("statistic"))) {
                        case "COUNT" -> count = value.doubleValue();
                        case "TOTAL_TIME" -> total = value.doubleValue();
                        case "MAX" -> max = value.doubleValue();
                        default -> {}
                    }
                }
            return Optional.of(new HttpMetrics((long) count, total, max));
        } catch (java.io.IOException | RuntimeException failure) {
            LOG.debug("Metrics of {} unavailable: {}", baseUrl, failure.toString());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private HttpResponse<String> get(String url) throws java.io.IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String body) {
        try {
            return json.readValue(body, Map.class);
        } catch (RuntimeException notJson) {
            return Map.of();
        }
    }
}
