package com.openhire.aigateway.domain;

import com.openhire.aigateway.domain.AiTypes.ModelRef;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Per-tenant AI configuration. Tasks are keyed by {@link AiTask#name()} because Mongo keys cannot contain dots. */
@Document("ai_settings")
public record AiSettings(
        @Id String tenantId,
        boolean localOnly,
        Map<String, TaskRoute> tasks,
        double monthlyBudgetInr,
        boolean showScoreToHiringManagers,
        boolean blindReviewDefault,
        Instant updatedAt,
        String updatedBy) {

    public record TaskRoute(String provider, String model, ModelRef fallback) {}
}
