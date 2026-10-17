package com.openhire.employee.application;

import java.time.Duration;
import java.util.List;

public record EmployeeSettings(
        String numberPrefix,
        List<Duration> retryDelays,
        List<Duration> hrisRetryDelays,
        Duration stuckAfter,
        Duration lease,
        Duration applicationPoll,
        int referralRewardDays) {}
