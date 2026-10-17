package com.openhire.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhire.platform.audit.AuditRecorder;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Opt-in wiring check against a local MongoDB and Redis (no Kafka needed: listeners do not start). Uses a uniquely
 * named oh_phase7_ctx_* database and drops it afterwards.
 */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "openhire.security.mode=local"})
class AnalyticsContextTest {
    private static final String DATABASE =
            "oh_phase7_ctx_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.mongodb.uri",
                () -> System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017/") + DATABASE);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
    }

    /** Exercise the real shared audit bean so constructor-wiring regressions fail this check. */
    @Autowired
    AuditRecorder auditRecorder;

    @Autowired
    WebApplicationContext context;

    @Autowired
    org.springframework.data.mongodb.core.MongoTemplate mongo;

    @AfterAll
    static void drop() {
        try (var client = com.mongodb.client.MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"))) {
            client.getDatabase(DATABASE).drop();
        }
    }

    @Test
    void theApplicationStartsAndServesEmptyAnalytics() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(get("/api/v1/analytics/kpis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.medianTimeToHireDays").value(0));
        mvc.perform(get("/api/v1/analytics/funnel?range=90d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobTitle").value("All jobs"))
                .andExpect(jsonPath("$.stages.length()").value(6));
        mvc.perform(get("/api/v1/analytics/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/platform/slo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/v1/platform/services")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/analytics/kpis?range=7d")).andExpect(status().isBadRequest());
    }
}
