package com.openhire.talent.application;

import java.time.Duration;

public record TalentSettings(
        int retentionMonths,
        int rediscoveryTop,
        int explainTop,
        int resultLimit,
        Duration cooldown,
        boolean internalMobility,
        Duration expiryWarning,
        Duration runCache) {}
