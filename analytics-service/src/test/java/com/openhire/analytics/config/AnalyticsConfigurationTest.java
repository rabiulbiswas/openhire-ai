package com.openhire.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsConfigurationTest {
    @Test
    void targetsKeepTheirConfiguredOrderAndLoseTrailingSlashes() {
        var targets = AnalyticsConfiguration.parseTargets(
                "identity-service=http://identity-service:8081, job-service=http://job-service:8082/ ,broken,=x,analytics=http://localhost:8096");

        assertThat(List.copyOf(targets.keySet())).containsExactly("identity-service", "job-service", "analytics");
        assertThat(targets.get("job-service")).isEqualTo("http://job-service:8082");
        assertThat(AnalyticsConfiguration.parseTargets("")).isEmpty();
    }
}
