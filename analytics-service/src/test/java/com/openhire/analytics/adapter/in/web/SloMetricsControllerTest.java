package com.openhire.analytics.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.analytics.application.PlatformQueryService;
import com.openhire.analytics.application.PlatformViews.Slo;
import com.openhire.support.Access;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SloMetricsControllerTest {
    @Test
    void secretIsRequiredAndNoDataIsNotExportedAsAHealthyZero() throws Exception {
        var platform = mock(PlatformQueryService.class);
        when(platform.slo(null))
                .thenReturn(List.of(
                        new Slo(
                                "public-api-availability",
                                "Availability",
                                99.5,
                                "24h",
                                99.9,
                                "%",
                                "MET",
                                80,
                                "10 samples",
                                false),
                        new Slo(
                                "application-api-latency",
                                "Latency",
                                500,
                                "24h",
                                0,
                                "ms",
                                "MET",
                                100,
                                "No data yet",
                                true)));
        when(platform.slo("a")).thenReturn(List.of());
        var controller = new SloMetricsController(platform, new Access("oidc", "demo", "secret"), "a");
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/internal/platform/metrics")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/internal/platform/metrics").header("X-Internal-Service-Secret", "wrong"))
                .andExpect(status().isUnauthorized());
        var body = mvc.perform(get("/api/v1/internal/platform/metrics").header("X-Internal-Service-Secret", "secret"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body)
                .contains("scope=\"global\",tenant_id=\"\",slo_id=\"public-api-availability\"} 99.9")
                .doesNotContain("application-api-latency");
        assertThat(SloMetricsController.escape("a\"\nb\\c")).isEqualTo("a\\\"\\nb\\\\c");
    }
}
