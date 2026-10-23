package com.openhire.employee.config;

import com.openhire.employee.service.EmployeeNumberAllocator;
import com.openhire.employee.service.EmployeeSettings;
import com.openhire.employee.service.port.EmployeePorts.EmployeeNumbers;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EmployeeConfiguration {
    @Bean
    EmployeeSettings employeeSettings(
            @Value("${openhire.employee.employee-no-prefix:EMP}") String prefix,
            @Value("${openhire.employee.retry-delays-seconds:15,60,600}") String retryDelays,
            @Value("${openhire.employee.hris-retry-delays-seconds:60,600,3600}") String hrisRetryDelays,
            @Value("${openhire.employee.stuck-after-hours:24}") long stuckAfterHours,
            @Value("${openhire.employee.lease-seconds:60}") long leaseSeconds,
            @Value("${openhire.employee.application-poll-seconds:30}") long applicationPollSeconds,
            @Value("${openhire.employee.referral-reward-days:90}") int rewardDays) {
        return new EmployeeSettings(
                prefix,
                seconds(retryDelays),
                seconds(hrisRetryDelays),
                Duration.ofHours(stuckAfterHours),
                Duration.ofSeconds(leaseSeconds),
                Duration.ofSeconds(applicationPollSeconds),
                rewardDays);
    }

    @Bean
    EmployeeNumberAllocator employeeNumberAllocator(EmployeeNumbers numbers, EmployeeSettings settings, Clock clock) {
        return new EmployeeNumberAllocator(numbers, settings.numberPrefix(), clock);
    }

    private static List<Duration> seconds(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                .toList();
    }
}
