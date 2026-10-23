package com.openhire.aigateway.service;

import java.time.Duration;

/** Gateway-wide defaults from {@code openhire.ai.*}; a tenant's stored settings override them. */
public record AiDefaults(
        String providerId,
        String chatModel,
        String embeddingModel,
        boolean localOnly,
        double monthlyBudgetInr,
        Duration cacheTtl,
        int contextTokens) {}
