package com.openhire.interview.controller;

import com.openhire.interview.dto.InterviewViews.PublicSchedulingView;
import com.openhire.interview.service.InterviewApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate self-scheduling. The signed link token travels in a header, not the path, so it does not
 * end up in gateway or proxy access logs.
 */
@RestController
@RequestMapping("/api/v1/public/scheduling")
class PublicSchedulingController {
    private final InterviewApplicationService interviews;

    PublicSchedulingController(InterviewApplicationService interviews) {
        this.interviews = interviews;
    }

    record BookInput(String start) {}

    record CancelInput(String reason) {}

    @GetMapping
    PublicSchedulingView view(@RequestHeader("X-Scheduling-Token") String token) {
        return interviews.publicView(token);
    }

    @PostMapping("/book")
    PublicSchedulingView book(@RequestHeader("X-Scheduling-Token") String token, @RequestBody BookInput input) {
        return interviews.publicBook(token, InterviewController.instant(input.start()));
    }

    @PostMapping("/alternatives")
    PublicSchedulingView alternatives(@RequestHeader("X-Scheduling-Token") String token) {
        return interviews.publicAlternatives(token);
    }

    @PostMapping("/cancel")
    PublicSchedulingView cancel(
            @RequestHeader("X-Scheduling-Token") String token, @RequestBody(required = false) CancelInput input) {
        return interviews.publicCancel(token, input == null ? null : input.reason());
    }
}
