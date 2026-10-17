package com.openhire.analytics.adapter.out.http;

import com.openhire.analytics.application.MonitoredSlos.Samples;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class PrometheusSamples implements Samples {
    private final String url;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    PrometheusSamples(@Value("${openhire.analytics.prometheus-url:}") String url, Clock clock) {
        this.url = url;
        this.clock = clock;
    }

    public Map<String, Double> current(String tenant) {
        if (url.isBlank()) return Map.of();
        try {
            var query = "openhire_slo_current{job=\"openhire-slo\",scope=\"" + (tenant == null ? "global" : "tenant")
                    + "\",tenant_id=" + json.writeValueAsString(tenant == null ? "" : tenant) + "}";
            var request = HttpRequest.newBuilder(
                            URI.create(url + "/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Map.of();
            var body = json.readTree(response.body());
            if (!"success".equals(body.path("status").asString())) return Map.of();
            var out = new HashMap<String, Double>();
            for (var row : body.path("data").path("result")) {
                var value = row.path("value");
                if (value.size() != 2) continue;
                double age = clock.instant().getEpochSecond() - value.get(0).asDouble();
                double current = Double.parseDouble(value.get(1).asString());
                if (age < 0 || age > 120 || !Double.isFinite(current)) continue;
                out.put(row.path("metric").path("slo_id").asString(), current);
            }
            return out;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception unavailable) {
            return Map.of();
        }
    }
}
