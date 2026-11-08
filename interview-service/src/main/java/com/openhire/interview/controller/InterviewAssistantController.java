package com.openhire.interview.controller;

import com.openhire.interview.service.InterviewAssistant;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class InterviewAssistantController {
    @GetMapping("/rounds/{id}/resume")
    Map<String, Object> resume(Authentication auth, @PathVariable String id) {
        return assistant.resume(viewers.tenant(auth), id, viewers.participant(auth));
    }

    private final InterviewAssistant assistant;
    private final Viewers viewers;

    InterviewAssistantController(InterviewAssistant assistant, Viewers viewers) {
        this.assistant = assistant;
        this.viewers = viewers;
    }

    record FeedbackInput(String notes) {}

    @GetMapping("/rounds/{id}/kit")
    Map<String, Object> kit(Authentication auth, @PathVariable String id) {
        return assistant.kit(viewers.tenant(auth), id, viewers.participant(auth));
    }

    @PostMapping("/rounds/{id}/ai-questions")
    Map<String, Object> questions(Authentication auth, @PathVariable String id) {
        return assistant.questions(viewers.tenant(auth), id, viewers.participant(auth));
    }

    @PostMapping("/rounds/{id}/feedback-draft")
    Map<String, Object> feedback(Authentication auth, @PathVariable String id, @RequestBody FeedbackInput input) {
        return assistant.feedback(viewers.tenant(auth), id, viewers.participant(auth), input.notes());
    }

    @PostMapping("/applications/{id}/ai-debrief")
    Map<String, Object> debrief(Authentication auth, @PathVariable String id) {
        return assistant.debrief(viewers.tenant(auth), id, viewers.participant(auth));
    }
}
