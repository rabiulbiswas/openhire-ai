package com.openhire.jobservice.api;

import java.util.List;

public record AiJobDraftResponse(
        String description, List<String> biasFlags, List<InterviewRound> suggestedInterviewPlan, String model) {
    public record InterviewRound(String id, String name, int durationMin, List<String> interviewerNames) {}
}
