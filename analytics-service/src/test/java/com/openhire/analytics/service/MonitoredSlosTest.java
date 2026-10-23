package com.openhire.analytics.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.analytics.dto.PlatformViews.Slo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonitoredSlosTest {
    @Test
    void prometheusValuesDriveStatusAndTenantScopeAndFallbackIsExplicit() {
        var platform = mock(PlatformQueryService.class);
        when(platform.slo("a"))
                .thenReturn(List.of(new Slo(
                        "public-api-availability",
                        "Availability",
                        99.5,
                        "24h",
                        100,
                        "%",
                        "MET",
                        100,
                        "observed health polls",
                        false)));
        var samples = mock(MonitoredSlos.Samples.class);
        when(samples.current("a")).thenReturn(Map.of("public-api-availability", 90.0));
        var result = new MonitoredSlos(platform, samples).read("a").getFirst();
        assertThat(result.current()).isEqualTo(90);
        assertThat(result.status()).isEqualTo("BREACHED");
        assertThat(result.basis()).startsWith("Prometheus snapshot");
        verify(samples).current("a");
        when(samples.current("a")).thenReturn(Map.of());
        result = new MonitoredSlos(platform, samples).read("a").getFirst();
        assertThat(result.current()).isEqualTo(100);
        assertThat(result.basis()).startsWith("Local fallback");
    }
}
