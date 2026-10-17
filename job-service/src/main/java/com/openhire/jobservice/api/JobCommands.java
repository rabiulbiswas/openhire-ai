package com.openhire.jobservice.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class JobCommands {
    private JobCommands() {}

    public record HiringTeamRequest(List<@Valid HiringTeamMember> hiringTeam) {}

    public record HiringTeamMember(@NotBlank String userId, @NotBlank String name, @NotBlank String role) {}

    public record InterviewPlanRequest(List<@Valid InterviewRound> interviewPlan) {}

    public record InterviewRound(
            @NotBlank String id, @NotBlank String name, int durationMin, List<@NotBlank String> interviewerNames) {}

    public record DecisionRequest(@NotBlank String decision, String reason, String actorId) {}
}
