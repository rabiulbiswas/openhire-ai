package com.openhire.interview.application;

import com.openhire.interview.domain.SlotFinder;
import java.time.Duration;
import java.time.ZoneId;

public record InterviewSettings(
        Duration feedbackWindow,
        int rescheduleLimit,
        Duration holdDuration,
        Duration linkValidity,
        String portalUrl,
        ZoneId defaultZone,
        SlotFinder.Policy policy) {}
