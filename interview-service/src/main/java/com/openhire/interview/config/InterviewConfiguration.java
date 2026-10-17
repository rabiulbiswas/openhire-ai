package com.openhire.interview.config;

import com.openhire.interview.application.InterviewSettings;
import com.openhire.interview.domain.SlotFinder;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class InterviewConfiguration {
    @Bean
    InterviewSettings interviewSettings(
            @Value("${openhire.interview.feedback-hours:24}") long feedbackHours,
            @Value("${openhire.interview.reschedule-limit:3}") int rescheduleLimit,
            @Value("${openhire.interview.hold-minutes:30}") long holdMinutes,
            @Value("${openhire.interview.link-days:7}") long linkDays,
            @Value("${openhire.interview.portal-url:http://localhost:3000}") String portalUrl,
            @Value("${openhire.interview.default-timezone:Asia/Kolkata}") String timezone,
            @Value("${openhire.interview.workday-start:09:00}") String dayStart,
            @Value("${openhire.interview.workday-end:18:00}") String dayEnd) {
        var standard = SlotFinder.Policy.standard();
        var policy = new SlotFinder.Policy(
                LocalTime.parse(dayStart),
                LocalTime.parse(dayEnd),
                standard.workdays(),
                standard.step(),
                standard.buffer(),
                standard.minimumNotice(),
                standard.horizonDays(),
                standard.maxSlots());
        return new InterviewSettings(
                Duration.ofHours(feedbackHours),
                rescheduleLimit,
                Duration.ofMinutes(holdMinutes),
                Duration.ofDays(linkDays),
                portalUrl.replaceAll("/+$", ""),
                ZoneId.of(timezone),
                policy);
    }
}
